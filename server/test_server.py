"""
اختبار شامل للخادم: يُشغَّل في GitHub Actions مع كل بناء.
    python3 server/test_server.py
"""
import asyncio
import base64
import os
import sys
import tempfile

from aiohttp.test_utils import TestClient, TestServer

sys.path.insert(0, os.path.dirname(__file__))
import dugy_server as ds  # noqa: E402


def b64(b: bytes) -> str:
    return base64.b64encode(b).decode()


KEYS = {
    "registrationId": 7, "identityKey": b64(b"I" * 33),
    "signedPreKeyId": 1, "signedPreKey": b64(b"S" * 33), "signedPreKeySig": b64(b"G" * 64),
    "kyberPreKeyId": 2, "kyberPreKey": b64(b"K" * 1569), "kyberPreKeySig": b64(b"H" * 64),
    "oneTimeKeys": [{"id": i, "pub": b64(bytes([i]) * 33)} for i in range(1, 4)],
}


async def recv(ws, kind, timeout=3):
    while True:
        m = await asyncio.wait_for(ws.receive_json(), timeout)
        if m.get("t") == kind:
            return m


async def main():
    tmp = tempfile.mkdtemp()
    db = ds.Db(os.path.join(tmp, "t.db"))
    client = TestClient(TestServer(ds.make_app(db)))
    await client.start_server()
    try:
        # إنشاء حساب بلا رمز دعوة مرفوض.
        r = await client.post("/v1/signup", json={"username": "ali", "password": "password1", "invite": "XXXX"})
        assert r.status == 403, r.status

        inv = ds.create_invite(db)
        r = await client.post("/v1/signup", json={"username": "ali", "password": "password1", "invite": inv.lower()})
        assert r.status == 200, await r.text()
        a = await r.json()

        # رمز الدعوة لاستخدام واحد.
        r = await client.post("/v1/signup", json={"username": "sara", "password": "password2", "invite": inv})
        assert r.status == 403
        r = await client.post("/v1/signup", json={"username": "sara", "password": "password2", "invite": ds.create_invite(db)})
        assert r.status == 200
        b = await r.json()

        # اسم محجوز.
        r = await client.post("/v1/signup", json={"username": "ali", "password": "password3", "invite": ds.create_invite(db)})
        assert r.status == 409

        ha = {"Authorization": "Bearer " + a["token"]}
        hb = {"Authorization": "Bearer " + b["token"]}

        # الدخول وكلمة مرور خاطئة.
        r = await client.post("/v1/signin", json={"username": "ali", "password": "nope-nope"})
        assert r.status == 401
        r = await client.post("/v1/signin", json={"username": "ali", "password": "password1"})
        assert r.status == 200
        r = await client.post("/v1/verify", json={"password": "password1"}, headers=ha)
        assert (await r.json())["ok"] is True
        r = await client.post("/v1/verify", json={"password": "wrong-one"}, headers=ha)
        assert (await r.json())["ok"] is False

        # بلا رمز جلسة.
        r = await client.get("/v1/users/sara")
        assert r.status == 401

        # المفاتيح العامة.
        r = await client.put("/v1/keys", json=KEYS, headers=hb)
        assert r.status == 200, await r.text()
        r = await client.get("/v1/users/sara", headers=ha)
        assert (await r.json())["uid"] == b["uid"]
        r = await client.get("/v1/users/nobody", headers=ha)
        assert r.status == 404
        r = await client.get(f"/v1/identity/{b['uid']}", headers=ha)
        assert (await r.json())["identityKey"] == KEYS["identityKey"]
        r = await client.post(f"/v1/bundle/{b['uid']}", headers=ha)
        bundle = await r.json()
        assert bundle["kyberPreKey"] == KEYS["kyberPreKey"] and bundle["oneTimeKey"]["id"] == 1
        r = await client.get("/v1/onetime/count", headers=hb)
        assert (await r.json())["count"] == 2
        r = await client.post("/v1/onetime", json={"keys": [{"id": 9, "pub": b64(b"x" * 33)}]}, headers=hb)
        r = await client.get("/v1/onetime/count", headers=hb)
        assert (await r.json())["count"] == 3

        # التمرير: المستلم غير متصل -> «offline» ثم «online» عند اتصاله.
        wa = await client.ws_connect("/v1/ws", headers=ha)
        await wa.send_json({"t": "send", "to": b["uid"], "id": "m1", "type": 3, "body": b64(b"cipher"), "sentAt": 1})
        off = await recv(wa, "offline")
        assert off["id"] == "m1"

        wb = await client.ws_connect("/v1/ws", headers=hb)
        on = await recv(wa, "online")
        assert on["uid"] == b["uid"]

        await wa.send_json({"t": "send", "to": b["uid"], "id": "m1", "type": 3, "body": b64(b"cipher"), "sentAt": 1})
        msg = await recv(wb, "msg")
        assert msg["from"] == a["uid"] and msg["fromName"] == "ali" and msg["body"] == b64(b"cipher")
        await wb.send_json({"t": "ack", "id": "m1"})
        d = await recv(wa, "delivered")
        assert d["id"] == "m1"

        # مستلم غير موجود.
        await wa.send_json({"t": "send", "to": "nope", "id": "m2", "type": 3, "body": b64(b"c")})
        assert (await recv(wa, "unknown"))["id"] == "m2"

        # تُمرَّر ولم يؤكد المستلم ثم انقطع -> يُبلَّغ المرسل عند عودته.
        await wa.send_json({"t": "send", "to": b["uid"], "id": "m3", "type": 3, "body": b64(b"c3")})
        await recv(wb, "msg")
        await wb.close()
        await asyncio.sleep(0.2)
        wb = await client.ws_connect("/v1/ws", headers=hb)
        assert (await recv(wa, "online"))["uid"] == b["uid"]

        # لا شيء من الرسائل في قاعدة البيانات.
        tables = [t[0] for t in db.all("SELECT name FROM sqlite_master WHERE type='table'")]
        assert sorted(tables) == ["invites", "keys", "onetime", "tokens", "users"], tables
        raw = open(os.path.join(tmp, "t.db"), "rb").read()
        assert b"cipher" not in raw

        # الخروج يبطل الرمز.
        await client.post("/v1/signout", headers=ha)
        r = await client.get("/v1/users/sara", headers=ha)
        assert r.status == 401

        # تحديد محاولات الدخول.
        for _ in range(ds.FAIL_LIMIT):
            await client.post("/v1/signin", json={"username": "sara", "password": "bad-pass"})
        r = await client.post("/v1/signin", json={"username": "sara", "password": "password2"})
        assert r.status == 429

        await wa.close()
        await wb.close()
        print("SERVER TESTS PASSED")
    finally:
        await client.close()


if __name__ == "__main__":
    asyncio.run(main())
