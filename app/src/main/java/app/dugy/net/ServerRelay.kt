package app.dugy.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import app.dugy.BuildConfig
import app.dugy.e2e.Envelope
import app.dugy.e2e.PublicPreKey
import app.dugy.e2e.PublishedKeys
import app.dugy.e2e.RemoteBundle
import app.dugy.security.KeystoreCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** رسالة مشفّرة وصلت من الخادم. */
class IncomingEnvelope(
    val id: String,
    val fromUid: String,
    val fromName: String,
    val sentAt: Long,
    val envelope: Envelope,
)

class RelayException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        USERNAME_TAKEN, WEAK_PASSWORD, WRONG_CREDENTIALS, NO_SUCH_USER, BAD_INVITE,
        RATE_LIMITED, UNAUTHORIZED, NETWORK, OTHER,
    }
}

/** أحداث الاتصال الحي بالخادم. تُستدعى كلها على الخيط الرئيسي. */
interface LinkListener {
    fun onConnected()
    fun onDisconnected()
    fun onMessage(m: IncomingEnvelope)
    fun onDelivered(id: String)
    fun onOffline(id: String, toUid: String)
    fun onOnline(uid: String)
    fun onUnknown(id: String)
}

/**
 * الاتصال بخادم DUGY على الراسبيري (عبر شبكة Tailscale الخاصة).
 *
 * الخادم لا يحفظ أي رسالة: يمرّرها فورًا عبر الاتصال الحي إن كان المستلم متصلًا، وإلا
 * يخبرنا فتبقى الرسالة المشفّرة في هاتفنا حتى يتصل. يحفظ فقط الحسابات والمفاتيح العامة.
 */
class ServerRelay(context: Context) {
    private val appContext = context.applicationContext
    private val base = BuildConfig.SERVER_URL.trim().trimEnd('/')
    val isConfigured: Boolean = base.startsWith("https://")

    private val prefs = appContext.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ---------------- الجلسة المحفوظة ----------------

    private class Session(val uid: String, val username: String, val token: String)

    @Volatile private var session: Session? = loadSession()

    val currentUid: String? get() = session?.uid
    val currentUsername: String? get() = session?.username

    private fun loadSession(): Session? {
        val blob = prefs.getString(K_SESSION, null) ?: return null
        return try {
            val json = JSONObject(String(KeystoreCipher.decrypt(Base64.decode(blob, Base64.NO_WRAP))))
            Session(json.getString("uid"), json.getString("username"), json.getString("token"))
        } catch (e: Exception) {
            null
        }
    }

    private fun saveSession(s: Session?) {
        session = s
        if (s == null) {
            prefs.edit().remove(K_SESSION).commit()
            return
        }
        val json = JSONObject().put("uid", s.uid).put("username", s.username).put("token", s.token)
        val blob = KeystoreCipher.encrypt(appContext, json.toString().toByteArray())
        prefs.edit().putString(K_SESSION, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }

    // ---------------- الحساب ----------------

    suspend fun signUp(username: String, password: String, invite: String): String {
        val res = call(
            "POST", "/v1/signup",
            JSONObject().put("username", username).put("password", password).put("invite", invite),
            auth = false,
        )
        saveSession(Session(res.getString("uid"), res.getString("username"), res.getString("token")))
        return res.getString("uid")
    }

    suspend fun signIn(username: String, password: String): String {
        val res = call(
            "POST", "/v1/signin",
            JSONObject().put("username", username).put("password", password),
            auth = false,
        )
        saveSession(Session(res.getString("uid"), res.getString("username"), res.getString("token")))
        return res.getString("uid")
    }

    /** يمسح الجلسة من الهاتف فورًا، ويبطلها على الخادم إن أمكن. */
    fun signOut() {
        disconnect()
        val token = session?.token
        saveSession(null)
        if (token != null && isConfigured) {
            val req = Request.Builder()
                .url("$base/v1/signout")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON))
                .build()
            http.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) = Unit
                override fun onResponse(call: okhttp3.Call, response: Response) = response.close()
            })
        }
    }

    suspend fun verifyPassword(username: String, password: String): Boolean {
        if (session?.username != username) return false
        return call("POST", "/v1/verify", JSONObject().put("password", password)).optBoolean("ok", false)
    }

    // ---------------- المفاتيح العامة ----------------

    @Suppress("UNUSED_PARAMETER")
    suspend fun publishKeys(uid: String, username: String, keys: PublishedKeys) {
        val body = JSONObject()
            .put("registrationId", keys.registrationId)
            .put("identityKey", b64(keys.identityKey))
            .put("signedPreKeyId", keys.signedPreKeyId)
            .put("signedPreKey", b64(keys.signedPreKey))
            .put("signedPreKeySig", b64(keys.signedPreKeySignature))
            .put("kyberPreKeyId", keys.kyberPreKeyId)
            .put("kyberPreKey", b64(keys.kyberPreKey))
            .put("kyberPreKeySig", b64(keys.kyberPreKeySignature))
            .put("oneTimeKeys", keysJson(keys.oneTimePreKeys))
        call("PUT", "/v1/keys", body)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun uploadOneTimeKeys(uid: String, keys: List<PublicPreKey>) {
        call("POST", "/v1/onetime", JSONObject().put("keys", keysJson(keys)))
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun oneTimeKeyCount(uid: String): Long = call("GET", "/v1/onetime/count").getLong("count")

    suspend fun lookupUid(username: String): String? =
        callOrNull("GET", "/v1/users/$username")?.getString("uid")

    suspend fun publishedIdentity(uid: String): ByteArray? =
        callOrNull("GET", "/v1/identity/$uid")?.let { unb64(it.getString("identityKey")) }

    suspend fun fetchBundle(uid: String): RemoteBundle? {
        val d = callOrNull("POST", "/v1/bundle/$uid", JSONObject()) ?: return null
        val ot = d.optJSONObject("oneTimeKey")
        return RemoteBundle(
            registrationId = d.getInt("registrationId"),
            identityKey = unb64(d.getString("identityKey")),
            signedPreKeyId = d.getInt("signedPreKeyId"),
            signedPreKey = unb64(d.getString("signedPreKey")),
            signedPreKeySignature = unb64(d.getString("signedPreKeySig")),
            kyberPreKeyId = d.getInt("kyberPreKeyId"),
            kyberPreKey = unb64(d.getString("kyberPreKey")),
            kyberPreKeySignature = unb64(d.getString("kyberPreKeySig")),
            oneTimePreKey = ot?.let { PublicPreKey(it.getInt("id"), unb64(it.getString("pub"))) },
        )
    }

    // ---------------- الاتصال الحي ----------------

    private var listener: LinkListener? = null
    private var socket: WebSocket? = null
    @Volatile private var wanted = false
    @Volatile var connected = false
        private set
    private var attempt = 0
    private val reconnect = Runnable { open() }

    /** يبقي اتصالًا حيًّا بالخادم ويعيد الاتصال تلقائيًا عند انقطاعه. */
    fun connect(l: LinkListener) {
        main.post {
            listener = l
            wanted = true
            open()
        }
    }

    fun disconnect() {
        main.post {
            wanted = false
            main.removeCallbacks(reconnect)
            socket?.close(1000, null)
            socket = null
            if (connected) {
                connected = false
                listener?.onDisconnected()
            }
        }
    }

    /** محاولة فورية (مثلًا عند عودة الإنترنت). */
    fun reconnectNow() {
        main.post {
            if (!wanted || socket != null) return@post
            main.removeCallbacks(reconnect)
            attempt = 0
            open()
        }
    }

    private fun open() {
        if (!wanted || socket != null || !isConfigured) return
        val token = session?.token ?: return
        val req = Request.Builder()
            .url("$base/v1/ws")
            .header("Authorization", "Bearer $token")
            .build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (socket !== webSocket) return@post
                    attempt = 0
                    connected = true
                    listener?.onConnected()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val d = runCatching { JSONObject(text) }.getOrNull() ?: return
                main.post {
                    if (socket !== webSocket) return@post
                    dispatch(d)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
        })
    }

    private fun dropped(ws: WebSocket) {
        main.post {
            if (socket !== ws) return@post
            socket = null
            if (connected) {
                connected = false
                listener?.onDisconnected()
            }
            if (wanted) {
                attempt++
                val delayMs = (1000L shl minOf(attempt, 5)).coerceAtMost(30_000L)
                main.postDelayed(reconnect, delayMs)
            }
        }
    }

    private fun dispatch(d: JSONObject) {
        val l = listener ?: return
        when (d.optString("t")) {
            "msg" -> {
                val body = runCatching { unb64(d.getString("body")) }.getOrNull() ?: return
                l.onMessage(
                    IncomingEnvelope(
                        id = d.optString("id"),
                        fromUid = d.optString("from"),
                        fromName = d.optString("fromName"),
                        sentAt = d.optLong("sentAt", System.currentTimeMillis()),
                        envelope = Envelope(d.optInt("type"), body),
                    )
                )
            }
            "delivered" -> l.onDelivered(d.optString("id"))
            "offline" -> l.onOffline(d.optString("id"), d.optString("to"))
            "online" -> l.onOnline(d.optString("uid"))
            "unknown" -> l.onUnknown(d.optString("id"))
        }
    }

    /** يرسل رسالة مشفّرة عبر الاتصال الحي. يعيد false إن لم يكن متصلًا. */
    fun send(toUid: String, messageId: String, envelope: Envelope, sentAt: Long): Boolean {
        val ws = socket ?: return false
        if (!connected) return false
        val frame = JSONObject()
            .put("t", "send")
            .put("to", toUid)
            .put("id", messageId)
            .put("type", envelope.type)
            .put("body", b64(envelope.body))
            .put("sentAt", sentAt)
        return ws.send(frame.toString())
    }

    /** تأكيد حفظ رسالة واردة على هذا الهاتف، فيُبلَّغ المرسل بالتسليم. */
    fun ack(messageId: String) {
        socket?.send(JSONObject().put("t", "ack").put("id", messageId).toString())
    }

    // ---------------- HTTP ----------------

    private suspend fun callOrNull(method: String, path: String, body: JSONObject? = null): JSONObject? = try {
        call(method, path, body)
    } catch (e: RelayException) {
        if (e.reason == RelayException.Reason.NO_SUCH_USER) null else throw e
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            if (!isConfigured) throw RelayException(RelayException.Reason.OTHER)
            val builder = Request.Builder().url(base + path)
            if (auth) {
                val token = session?.token ?: throw RelayException(RelayException.Reason.UNAUTHORIZED)
                builder.header("Authorization", "Bearer $token")
            }
            val reqBody = body?.toString()?.toRequestBody(JSON)
            builder.method(method, reqBody ?: if (method == "GET") null else "{}".toRequestBody(JSON))
            val response = try {
                http.newCall(builder.build()).execute()
            } catch (e: IOException) {
                throw RelayException(RelayException.Reason.NETWORK, e)
            }
            response.use { r ->
                val text = try {
                    r.body?.string().orEmpty()
                } catch (e: IOException) {
                    throw RelayException(RelayException.Reason.NETWORK, e)
                }
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (r.isSuccessful) return@withContext json
                val reason = when (json.optString("error")) {
                    "username_taken" -> RelayException.Reason.USERNAME_TAKEN
                    "weak_password", "bad_username" -> RelayException.Reason.WEAK_PASSWORD
                    "wrong_credentials" -> RelayException.Reason.WRONG_CREDENTIALS
                    "bad_invite" -> RelayException.Reason.BAD_INVITE
                    "rate_limited" -> RelayException.Reason.RATE_LIMITED
                    "not_found" -> RelayException.Reason.NO_SUCH_USER
                    "unauthorized" -> RelayException.Reason.UNAUTHORIZED
                    else -> if (r.code >= 500) RelayException.Reason.NETWORK else RelayException.Reason.OTHER
                }
                throw RelayException(reason)
            }
        }

    private fun keysJson(keys: List<PublicPreKey>) = JSONArray().also { arr ->
        keys.forEach { arr.put(JSONObject().put("id", it.id).put("pub", b64(it.publicKey))) }
    }

    companion object {
        private const val K_SESSION = "session"
        private val JSON = "application/json".toMediaType()

        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

        private val USERNAME_RE = Regex("^[a-z0-9_]{3,20}$")
        fun normalizeUsername(raw: String) = raw.trim().lowercase()
        fun isValidUsername(u: String) = USERNAME_RE.matches(u)
    }
}
