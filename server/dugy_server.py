#!/usr/bin/env python3
"""
خادم DUGY الصغير للراسبيري.

ما يُحفظ على القرص فقط:
  - الحسابات: اسم المستخدم، وبصمة كلمة المرور (scrypt)، ورموز الجلسات (بصمتها لا نصها).
  - المفاتيح العامة لكل حساب (لبدء محادثة مشفّرة معه وهو غير متصل).
  - رموز الدعوة (عشوائية، لاستخدام واحد، تنتهي صلاحيتها).

ما لا يُحفظ أبدًا: الرسائل. الخادم يستقبل الرسالة المشفّرة ويمرّرها فورًا إلى هاتف
المستلم إن كان متصلًا، وإلا يخبر المرسل أن المستلم غير متصل، فتبقى الرسالة في هاتف
المرسل حتى يتصل الاثنان معًا. لا يستطيع الخادم قراءة أي رسالة.

يستمع على 127.0.0.1 فقط. الوصول إليه من الشبكة الخاصة يتم عبر «tailscale serve».
"""

import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import sqlite3
import sys
import time

from aiohttp import WSMsgType, web

LOG = logging.getLogger("dugy")

USERNAME_RE = re.compile(r"^[a-z0-9_]{3,20}$")
MAX_BODY = 256 * 1024
MAX_ONE_TIME = 200
INVITE_TTL = 48 * 3600

# محاولات الدخول الفاشلة: الحد ونافذته.
FAIL_LIMIT = 10
FAIL_WINDOW = 15 * 60

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    uid TEXT PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    pw_salt BLOB NOT NULL,
    pw_hash BLOB NOT NULL,
    created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS tokens (
    hash BLOB PRIMARY KEY,
    uid TEXT NOT NULL,
    created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS keys (
    uid TEXT PRIMARY KEY,
    registration_id INTEGER NOT NULL,
    identity BLOB NOT NULL,
    spk_id INTEGER NOT NULL,
    spk BLOB NOT NULL,
    spk_sig BLOB NOT NULL,
    kyber_id INTEGER NOT NULL,
    kyber BLOB NOT NULL,
    kyber_sig BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS onetime (
    uid TEXT NOT NULL,
    key_id INTEGER NOT NULL,
    pub BLOB NOT NULL,
    PRIMARY KEY (uid, key_id)
);
CREATE TABLE IF NOT EXISTS invites (
    hash BLOB PRIMARY KEY,
    expires INTEGER NOT NULL
);
"""


# ---------------------------------------------------------------- أدوات

def b64e(b: bytes) -> str:
    return base64.b64encode(b).decode()


def b64d(s, limit=MAX_BODY) -> bytes:
    if not isinstance(s, str) or len(s) > limit * 2:
        raise ValueError("bad base64")
    out = base64.b64decode(s, validate=True)
    if len(out) > limit:
        raise ValueError("too large")
    return out


def sha256(b: bytes) -> bytes:
    return hashlib.sha256(b).digest()


def hash_password(password: str, salt: bytes) -> bytes:
    return hashlib.scrypt(password.encode(), salt=salt, n=2 ** 14, r=8, p=1, dklen=32)


def normalize_invite(code: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", (code or "").upper())


def err(status: int, code: str):
    return web.json_response({"error": code}, status=status)


class Db:
    def __init__(self, path: str):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self.conn = sqlite3.connect(path, isolation_level=None)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA secure_delete=ON")
        self.conn.executescript(SCHEMA)

    def one(self, sql, *args):
        return self.conn.execute(sql, args).fetchone()

    def all(self, sql, *args):
        return self.conn.execute(sql, args).fetchall()

    def run(self, sql, *args):
        return self.conn.execute(sql, args)

    def tx(self):
        return _Tx(self.conn)


class _Taken(Exception):
    pass


class _BadInvite(Exception):
    pass


class _Tx:
    def __init__(self, conn):
        self.conn = conn

    def __enter__(self):
        self.conn.execute("BEGIN IMMEDIATE")
        return self.conn

    def __exit__(self, exc_type, exc, tb):
        self.conn.execute("ROLLBACK" if exc_type else "COMMIT")
        return False


# ---------------------------------------------------------------- رموز الدعوة

def create_invite(db: Db) -> str:
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    raw = "".join(secrets.choice(alphabet) for _ in range(12))
    db.run("DELETE FROM invites WHERE expires < ?", int(time.time()))
    db.run("INSERT INTO invites(hash, expires) VALUES (?, ?)", sha256(raw.encode()), int(time.time()) + INVITE_TTL)
    return f"{raw[0:4]}-{raw[4:8]}-{raw[8:12]}"


# ---------------------------------------------------------------- الخادم

class Server:
    def __init__(self, db: Db):
        self.db = db
        # الاتصالات الحية فقط، في الذاكرة: uid -> مجموعة اتصالات.
        self.conns: dict[str, set[web.WebSocketResponse]] = {}
        # من ينتظر اتصال من: uid المستلم -> مجموعة uid المرسلين. في الذاكرة فقط.
        self.waiters: dict[str, set[str]] = {}
        # رسائل مُرّرت ولم يؤكد المستلم حفظها بعد: id -> uid المرسل. في الذاكرة فقط.
        self.in_flight: dict[tuple[str, str], str] = {}
        self.fails: dict[str, list[float]] = {}

    # ---------- محاولات الدخول ----------

    def _blocked(self, key: str) -> bool:
        now = time.time()
        recent = [t for t in self.fails.get(key, []) if now - t < FAIL_WINDOW]
        self.fails[key] = recent
        return len(recent) >= FAIL_LIMIT

    def _fail(self, key: str):
        self.fails.setdefault(key, []).append(time.time())

    @staticmethod
    def _client(request: web.Request) -> str:
        # خلف «tailscale serve» يأتي الاتصال من 127.0.0.1؛ نستخدم عنوان الشبكة الخاصة إن وُجد.
        return request.headers.get("X-Forwarded-For", request.remote or "?").split(",")[0].strip()

    # ---------- المصادقة ----------

    def _uid_for(self, request: web.Request):
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return None
        row = self.db.one("SELECT uid FROM tokens WHERE hash = ?", sha256(auth[7:].encode()))
        return row[0] if row else None

    def _issue_token(self, uid: str) -> str:
        token = secrets.token_urlsafe(32)
        self.db.run("INSERT INTO tokens(hash, uid, created) VALUES (?, ?, ?)", sha256(token.encode()), uid, int(time.time()))
        return token

    async def _json(self, request: web.Request) -> dict:
        try:
            data = await request.json()
        except Exception:
            raise web.HTTPBadRequest(text='{"error":"bad_request"}', content_type="application/json")
        if not isinstance(data, dict):
            raise web.HTTPBadRequest(text='{"error":"bad_request"}', content_type="application/json")
        return data

    def _require(self, request):
        uid = self._uid_for(request)
        if uid is None:
            raise web.HTTPUnauthorized(text='{"error":"unauthorized"}', content_type="application/json")
        return uid

    # ---------- الحساب ----------

    async def signup(self, request):
        client = self._client(request)
        if self._blocked("ip:" + client):
            return err(429, "rate_limited")
        data = await self._json(request)
        username = str(data.get("username", "")).strip().lower()
        password = str(data.get("password", ""))
        invite = normalize_invite(str(data.get("invite", "")))
        if not USERNAME_RE.match(username):
            return err(400, "bad_username")
        if len(password) < 8:
            return err(400, "weak_password")

        inv_hash = sha256(invite.encode())
        row = self.db.one("SELECT expires FROM invites WHERE hash = ?", inv_hash)
        if not invite or row is None or row[0] < time.time():
            self._fail("ip:" + client)
            return err(403, "bad_invite")
        if self.db.one("SELECT 1 FROM users WHERE username = ?", username):
            return err(409, "username_taken")
        # الحساب الثقيل خارج المعاملة (لا انتظار داخلها).
        salt = secrets.token_bytes(16)
        pw = await asyncio.get_running_loop().run_in_executor(None, hash_password, password, salt)
        uid = secrets.token_hex(16)
        try:
            self._create_user(uid, username, salt, pw, inv_hash)
        except _Taken:
            return err(409, "username_taken")
        except _BadInvite:
            return err(403, "bad_invite")
        LOG.info("account created")
        return web.json_response({"uid": uid, "username": username, "token": self._issue_token(uid)})

    def _create_user(self, uid, username, salt, pw, inv_hash):
        with self.db.tx() as c:
            # نعيد التحقق داخل المعاملة: الرمز لاستخدام واحد والاسم فريد.
            if c.execute("DELETE FROM invites WHERE hash = ? AND expires >= ?", (inv_hash, int(time.time()))).rowcount != 1:
                raise _BadInvite()
            if c.execute("SELECT 1 FROM users WHERE username = ?", (username,)).fetchone():
                raise _Taken()
            c.execute("INSERT INTO users(uid, username, pw_salt, pw_hash, created) VALUES (?,?,?,?,?)",
                      (uid, username, salt, pw, int(time.time())))

    async def _check_password(self, username: str, password: str):
        row = self.db.one("SELECT uid, pw_salt, pw_hash FROM users WHERE username = ?", username)
        if row is None:
            # نفس الكلفة تقريبًا حتى لا يُعرف هل الاسم موجود من زمن الاستجابة.
            await asyncio.get_running_loop().run_in_executor(None, hash_password, password, b"0" * 16)
            return None
        uid, salt, expected = row
        actual = await asyncio.get_running_loop().run_in_executor(None, hash_password, password, salt)
        return uid if hmac.compare_digest(actual, expected) else None

    async def signin(self, request):
        client = self._client(request)
        data = await self._json(request)
        username = str(data.get("username", "")).strip().lower()
        password = str(data.get("password", ""))
        if self._blocked("ip:" + client) or self._blocked("user:" + username):
            return err(429, "rate_limited")
        uid = await self._check_password(username, password)
        if uid is None:
            self._fail("ip:" + client)
            self._fail("user:" + username)
            return err(401, "wrong_credentials")
        return web.json_response({"uid": uid, "username": username, "token": self._issue_token(uid)})

    async def verify(self, request):
        uid = self._require(request)
        data = await self._json(request)
        row = self.db.one("SELECT username FROM users WHERE uid = ?", uid)
        if row is None or self._blocked("user:" + row[0]):
            return err(429, "rate_limited")
        ok = await self._check_password(row[0], str(data.get("password", ""))) == uid
        if not ok:
            self._fail("user:" + row[0])
        return web.json_response({"ok": ok})

    async def signout(self, request):
        auth = request.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            self.db.run("DELETE FROM tokens WHERE hash = ?", sha256(auth[7:].encode()))
        return web.json_response({"ok": True})

    # ---------- المفاتيح العامة ----------

    async def put_keys(self, request):
        uid = self._require(request)
        d = await self._json(request)
        try:
            row = (
                uid, int(d["registrationId"]), b64d(d["identityKey"], 4096),
                int(d["signedPreKeyId"]), b64d(d["signedPreKey"], 4096), b64d(d["signedPreKeySig"], 4096),
                int(d["kyberPreKeyId"]), b64d(d["kyberPreKey"], 8192), b64d(d["kyberPreKeySig"], 4096),
            )
            one_time = [(uid, int(k["id"]), b64d(k["pub"], 4096)) for k in d.get("oneTimeKeys", [])][:MAX_ONE_TIME]
        except (KeyError, TypeError, ValueError):
            return err(400, "bad_request")
        with self.db.tx() as c:
            c.execute("INSERT OR REPLACE INTO keys VALUES (?,?,?,?,?,?,?,?,?)", row)
            c.execute("DELETE FROM onetime WHERE uid = ?", (uid,))
            c.executemany("INSERT OR REPLACE INTO onetime VALUES (?,?,?)", one_time)
        return web.json_response({"ok": True})

    async def add_onetime(self, request):
        uid = self._require(request)
        d = await self._json(request)
        try:
            keys = [(uid, int(k["id"]), b64d(k["pub"], 4096)) for k in d.get("keys", [])][:MAX_ONE_TIME]
        except (KeyError, TypeError, ValueError):
            return err(400, "bad_request")
        self.db.conn.executemany("INSERT OR REPLACE INTO onetime VALUES (?,?,?)", keys)
        return web.json_response({"ok": True})

    async def count_onetime(self, request):
        uid = self._require(request)
        n = self.db.one("SELECT COUNT(*) FROM onetime WHERE uid = ?", uid)[0]
        return web.json_response({"count": n})

    async def lookup(self, request):
        self._require(request)
        row = self.db.one("SELECT uid FROM users WHERE username = ?", request.match_info["username"].lower())
        return web.json_response({"uid": row[0]}) if row else err(404, "not_found")

    async def identity(self, request):
        self._require(request)
        row = self.db.one("SELECT identity FROM keys WHERE uid = ?", request.match_info["uid"])
        return web.json_response({"identityKey": b64e(row[0])}) if row else err(404, "not_found")

    async def bundle(self, request):
        self._require(request)
        target = request.match_info["uid"]
        with self.db.tx() as c:
            k = c.execute("SELECT * FROM keys WHERE uid = ?", (target,)).fetchone()
            if k is None:
                return err(404, "not_found")
            ot = c.execute("SELECT key_id, pub FROM onetime WHERE uid = ? LIMIT 1", (target,)).fetchone()
            if ot:
                c.execute("DELETE FROM onetime WHERE uid = ? AND key_id = ?", (target, ot[0]))
        out = {
            "registrationId": k[1], "identityKey": b64e(k[2]),
            "signedPreKeyId": k[3], "signedPreKey": b64e(k[4]), "signedPreKeySig": b64e(k[5]),
            "kyberPreKeyId": k[6], "kyberPreKey": b64e(k[7]), "kyberPreKeySig": b64e(k[8]),
        }
        if ot:
            out["oneTimeKey"] = {"id": ot[0], "pub": b64e(ot[1])}
        return web.json_response(out)

    # ---------- التمرير الفوري (بلا تخزين) ----------

    async def _push(self, uid: str, payload: dict) -> bool:
        sent = False
        for ws in list(self.conns.get(uid, ())):
            try:
                await ws.send_json(payload)
                sent = True
            except Exception:
                pass
        return sent

    async def ws(self, request):
        uid = self._require(request)
        name_row = self.db.one("SELECT username FROM users WHERE uid = ?", uid)
        if name_row is None:
            return err(401, "unauthorized")
        username = name_row[0]

        ws = web.WebSocketResponse(heartbeat=25, max_msg_size=MAX_BODY * 2)
        await ws.prepare(request)
        self.conns.setdefault(uid, set()).add(ws)

        # من كان ينتظر هذا المستخدم يُبلَّغ الآن ليرسل ما عنده.
        for sender in self.waiters.pop(uid, set()):
            await self._push(sender, {"t": "online", "uid": uid})

        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                try:
                    d = json.loads(msg.data)
                    kind = d.get("t")
                    if kind == "send":
                        await self._on_send(uid, username, d)
                    elif kind == "ack":
                        await self._on_ack(uid, d)
                except Exception:
                    LOG.debug("bad frame", exc_info=True)
        finally:
            conns = self.conns.get(uid)
            if conns is not None:
                conns.discard(ws)
                if not conns:
                    del self.conns[uid]
                    # ما لم يؤكد استلامه يبقى عند مرسله؛ نخبره عند عودة المستلم ليعيد إرساله.
                    for key in [k for k in self.in_flight if k[0] == uid]:
                        self.waiters.setdefault(uid, set()).add(self.in_flight.pop(key))
        return ws

    async def _on_send(self, uid: str, username: str, d: dict):
        to = str(d["to"])
        mid = str(d["id"])[:64]
        body = d["body"]
        b64d(body)  # تحقق من الصيغة والحجم فقط؛ المحتوى مشفّر ولا يُقرأ.
        frame = {
            "t": "msg", "id": mid, "from": uid, "fromName": username,
            "type": int(d.get("type", 0)), "body": body, "sentAt": int(d.get("sentAt", time.time() * 1000)),
        }
        if self.db.one("SELECT 1 FROM users WHERE uid = ?", to) is None:
            await self._push(uid, {"t": "unknown", "id": mid, "to": to})
            return
        if to in self.conns:
            self.in_flight[(to, mid)] = uid
            if await self._push(to, frame):
                return
            self.in_flight.pop((to, mid), None)
        self.waiters.setdefault(to, set()).add(uid)
        await self._push(uid, {"t": "offline", "id": mid, "to": to})

    async def _on_ack(self, uid: str, d: dict):
        mid = str(d["id"])[:64]
        sender = self.in_flight.pop((uid, mid), None)
        if sender:
            await self._push(sender, {"t": "delivered", "id": mid})

    async def health(self, request):
        return web.json_response({"ok": True})


def make_app(db: Db) -> web.Application:
    s = Server(db)
    app = web.Application(client_max_size=1024 * 1024)
    app.add_routes([
        web.get("/v1/health", s.health),
        web.post("/v1/signup", s.signup),
        web.post("/v1/signin", s.signin),
        web.post("/v1/verify", s.verify),
        web.post("/v1/signout", s.signout),
        web.put("/v1/keys", s.put_keys),
        web.post("/v1/onetime", s.add_onetime),
        web.get("/v1/onetime/count", s.count_onetime),
        web.get("/v1/users/{username}", s.lookup),
        web.get("/v1/identity/{uid}", s.identity),
        web.post("/v1/bundle/{uid}", s.bundle),
        web.get("/v1/ws", s.ws),
    ])
    app["server"] = s
    return app


def main():
    p = argparse.ArgumentParser(description="DUGY relay server")
    p.add_argument("--db", default=os.environ.get("DUGY_DB", "/var/lib/dugy/dugy.db"))
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("command", nargs="?", choices=["serve", "invite"], default="serve")
    args = p.parse_args()

    db = Db(args.db)
    if args.command == "invite":
        code = create_invite(db)
        print(f"رمز الدعوة: {code}")
        print("صالح لاستخدام واحد خلال 48 ساعة.")
        return

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
    web.run_app(make_app(db), host=args.host, port=args.port, access_log=None)


if __name__ == "__main__":
    main()
