package app.dugy.net

import android.content.Context
import app.dugy.e2e.Envelope
import app.dugy.e2e.PublicPreKey
import app.dugy.e2e.PublishedKeys
import app.dugy.e2e.RemoteBundle
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

/** رسالة مشفّرة وصلت إلى صندوق هذا المستخدم على الخادم. */
class IncomingEnvelope(
    val id: String,
    val fromUid: String,
    val fromName: String,
    val sentAt: Long,
    val envelope: Envelope,
)

class RelayException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { USERNAME_TAKEN, WEAK_PASSWORD, WRONG_CREDENTIALS, NO_SUCH_USER, NETWORK, OTHER }
}

/**
 * طبقة الخادم على Firebase. لا يمرّ عبرها إلا:
 *  - المفاتيح **العامة** لكل مستخدم (للبدء بجلسة مشفّرة معه)،
 *  - والرسائل **المشفّرة** بين المستخدمين، وتُحذف من الخادم فور استلامها.
 * النص الصريح والمفاتيح الخاصة لا تصل إلى هذا الملف أصلًا.
 *
 * ما يراه الخادم رغم ذلك (بيانات وصفية): أسماء المستخدمين، ومن يراسل من ومتى،
 * والحجم التقريبي للرسائل، وعناوين IP. هذا موثّق للمستخدم في «حدود الحماية».
 *
 * قواعد الأمان المطابقة لهذا الملف في firebase/firestore.rules.
 */
class FirebaseRelay(context: Context) {

    /** هل أُضيف ملف google-services.json عند البناء؟ بدونه تبقى المراسلة معطّلة. */
    val isConfigured: Boolean = FirebaseApp.getApps(context.applicationContext).isNotEmpty()

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            // ذاكرة مؤقتة فقط: لا تُحفظ نسخة من بيانات الخادم على قرص الهاتف.
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        }
    }

    val currentUid: String? get() = if (isConfigured) auth.currentUser?.uid else null

    /** اسم المستخدم مشتق من البريد الصوري الذي أنشأنا به الحساب. */
    val currentUsername: String?
        get() = if (isConfigured) auth.currentUser?.email?.substringBefore('@') else null

    // ---------------- الحساب ----------------

    suspend fun signUp(username: String, password: String): String {
        // يُنشأ الحساب أولًا لأن قراءة الأسماء تتطلب تسجيل الدخول، ثم يُحجز الاسم في معاملة ذرية.
        val uid = guard { auth.createUserWithEmailAndPassword(emailFor(username), password).await() }
            .user?.uid ?: throw RelayException(RelayException.Reason.OTHER)
        try {
            guard {
                db.runTransaction<Any?> { tx ->
                    val ref = db.collection(USERNAMES).document(username)
                    if (tx.get(ref).exists()) throw FirebaseFirestoreException(
                        "taken", FirebaseFirestoreException.Code.ALREADY_EXISTS,
                    )
                    tx.set(ref, mapOf("uid" to uid))
                    null
                }.await()
            }
        } catch (e: Exception) {
            // لم نحجز الاسم: نحذف الحساب الناقص كي لا يبقى بلا اسم.
            runCatching { auth.currentUser?.delete()?.await() }
            auth.signOut()
            throw e
        }
        return uid
    }

    suspend fun signIn(username: String, password: String): String =
        guard { auth.signInWithEmailAndPassword(emailFor(username), password).await() }
            .user?.uid ?: throw RelayException(RelayException.Reason.OTHER)

    fun signOut() {
        if (isConfigured) auth.signOut()
    }

    /** يتحقق من كلمة مرور الحساب الحالي عبر الخادم. يرمي RelayException عند انقطاع الاتصال. */
    suspend fun verifyPassword(username: String, password: String): Boolean {
        val user = auth.currentUser ?: return false
        if (user.email?.substringBefore('@') != username) return false
        return try {
            guard { user.reauthenticate(EmailAuthProvider.getCredential(emailFor(username), password)).await() }
            true
        } catch (e: RelayException) {
            when (e.reason) {
                RelayException.Reason.WRONG_CREDENTIALS, RelayException.Reason.NO_SUCH_USER -> false
                else -> throw e
            }
        }
    }

    // ---------------- المفاتيح العامة ----------------

    /** ينشر مفاتيح هذا الجهاز العامة، ويستبدل أي مفاتيح قديمة لحساب المستخدم. */
    suspend fun publishKeys(uid: String, username: String, keys: PublishedKeys) {
        val user = db.collection(USERS).document(uid)
        guard {
            user.set(
                mapOf(
                    "username" to username,
                    "registrationId" to keys.registrationId,
                    "identityKey" to Blob.fromBytes(keys.identityKey),
                    "signedPreKeyId" to keys.signedPreKeyId,
                    "signedPreKey" to Blob.fromBytes(keys.signedPreKey),
                    "signedPreKeySig" to Blob.fromBytes(keys.signedPreKeySignature),
                    "kyberPreKeyId" to keys.kyberPreKeyId,
                    "kyberPreKey" to Blob.fromBytes(keys.kyberPreKey),
                    "kyberPreKeySig" to Blob.fromBytes(keys.kyberPreKeySignature),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        }
        // المفاتيح لمرة واحدة القديمة تخص جهازًا سابقًا ولا يمكن استخدامها بعد الآن.
        val old = guard { user.collection(ONE_TIME).get().await() }
        old.documents.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            guard { batch.commit().await() }
        }
        uploadOneTimeKeys(uid, keys.oneTimePreKeys)
    }

    suspend fun uploadOneTimeKeys(uid: String, keys: List<PublicPreKey>) {
        val col = db.collection(USERS).document(uid).collection(ONE_TIME)
        keys.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { k -> batch.set(col.document(k.id.toString()), mapOf("pub" to Blob.fromBytes(k.publicKey))) }
            guard { batch.commit().await() }
        }
    }

    suspend fun oneTimeKeyCount(uid: String): Long = guard {
        db.collection(USERS).document(uid).collection(ONE_TIME).count().get(AggregateSource.SERVER).await().count
    }

    suspend fun lookupUid(username: String): String? {
        val doc = guard { db.collection(USERNAMES).document(username).get().await() }
        return doc.getString("uid")
    }

    suspend fun publishedIdentity(uid: String): ByteArray? {
        val doc = guard { db.collection(USERS).document(uid).get().await() }
        return doc.getBlob("identityKey")?.toBytes()
    }

    /** يجلب مفاتيح جهة الاتصال العامة ويستهلك مفتاحًا واحدًا لمرة واحدة إن وُجد. */
    suspend fun fetchBundle(uid: String): RemoteBundle? {
        val user = db.collection(USERS).document(uid)
        val doc = guard { user.get().await() }
        if (!doc.exists()) return null
        var oneTime: PublicPreKey? = null
        repeat(3) {
            if (oneTime != null) return@repeat
            val candidate = guard { user.collection(ONE_TIME).limit(1).get().await() }.documents.firstOrNull()
                ?: return@repeat
            val claimed = runCatching {
                db.runTransaction<ByteArray?> { tx ->
                    val snap = tx.get(candidate.reference)
                    if (!snap.exists()) {
                        null
                    } else {
                        tx.delete(candidate.reference)
                        snap.getBlob("pub")?.toBytes()
                    }
                }.await()
            }.getOrNull()
            if (claimed != null) oneTime = PublicPreKey(candidate.id.toInt(), claimed)
        }
        return RemoteBundle(
            registrationId = (doc.getLong("registrationId") ?: return null).toInt(),
            identityKey = doc.getBlob("identityKey")?.toBytes() ?: return null,
            signedPreKeyId = (doc.getLong("signedPreKeyId") ?: return null).toInt(),
            signedPreKey = doc.getBlob("signedPreKey")?.toBytes() ?: return null,
            signedPreKeySignature = doc.getBlob("signedPreKeySig")?.toBytes() ?: return null,
            kyberPreKeyId = (doc.getLong("kyberPreKeyId") ?: return null).toInt(),
            kyberPreKey = doc.getBlob("kyberPreKey")?.toBytes() ?: return null,
            kyberPreKeySignature = doc.getBlob("kyberPreKeySig")?.toBytes() ?: return null,
            oneTimePreKey = oneTime,
        )
    }

    // ---------------- الرسائل المشفّرة ----------------

    /** يضع رسالة مشفّرة في صندوق المستلم. معرّف الرسالة يمنع التكرار عند إعادة المحاولة. */
    suspend fun send(fromUid: String, fromName: String, toUid: String, messageId: String, envelope: Envelope) {
        guard {
            db.collection(INBOX).document(toUid).collection(MESSAGES).document(messageId).set(
                mapOf(
                    "fromUid" to fromUid,
                    "fromName" to fromName,
                    "type" to envelope.type,
                    "body" to Blob.fromBytes(envelope.body),
                    "sentAt" to System.currentTimeMillis(),
                    "serverAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        }
    }

    fun listenInbox(
        uid: String,
        onMessages: (List<IncomingEnvelope>) -> Unit,
        onError: (Exception) -> Unit,
    ): ListenerRegistration =
        db.collection(INBOX).document(uid).collection(MESSAGES)
            .orderBy("sentAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                val added = snap?.documentChanges
                    ?.filter { it.type == DocumentChange.Type.ADDED }
                    ?.mapNotNull { change ->
                        val d = change.document
                        val body = d.getBlob("body")?.toBytes() ?: return@mapNotNull null
                        IncomingEnvelope(
                            id = d.id,
                            fromUid = d.getString("fromUid") ?: return@mapNotNull null,
                            fromName = d.getString("fromName") ?: return@mapNotNull null,
                            sentAt = d.getLong("sentAt") ?: System.currentTimeMillis(),
                            envelope = Envelope((d.getLong("type") ?: 0L).toInt(), body),
                        )
                    }
                    .orEmpty()
                if (added.isNotEmpty()) onMessages(added)
            }

    /** يراقب وصول عناصر جديدة إلى الصندوق دون قراءتها أو حذفها؛ يعيد معرّفاتها فقط. */
    fun watchInbox(uid: String, onNew: (List<String>) -> Unit): ListenerRegistration =
        db.collection(INBOX).document(uid).collection(MESSAGES)
            .addSnapshotListener { snap, error ->
                if (error != null) return@addSnapshotListener
                val ids = snap?.documentChanges
                    ?.filter { it.type == DocumentChange.Type.ADDED }
                    ?.map { it.document.id }
                    .orEmpty()
                if (ids.isNotEmpty()) onNew(ids)
            }

    /** يحذف الرسالة المشفّرة من الخادم بعد استلامها. */
    suspend fun deleteIncoming(uid: String, messageId: String) {
        runCatching {
            db.collection(INBOX).document(uid).collection(MESSAGES).document(messageId).delete().await()
        }
    }

    // ---------------- أدوات ----------------

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: RelayException) {
        throw e
    } catch (e: FirebaseAuthUserCollisionException) {
        throw RelayException(RelayException.Reason.USERNAME_TAKEN, e)
    } catch (e: FirebaseAuthWeakPasswordException) {
        throw RelayException(RelayException.Reason.WEAK_PASSWORD, e)
    } catch (e: FirebaseAuthInvalidUserException) {
        throw RelayException(RelayException.Reason.NO_SUCH_USER, e)
    } catch (e: FirebaseAuthInvalidCredentialsException) {
        throw RelayException(RelayException.Reason.WRONG_CREDENTIALS, e)
    } catch (e: FirebaseFirestoreException) {
        val reason = when (e.code) {
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> RelayException.Reason.NETWORK
            FirebaseFirestoreException.Code.ALREADY_EXISTS -> RelayException.Reason.USERNAME_TAKEN
            else -> RelayException.Reason.OTHER
        }
        throw RelayException(reason, e)
    } catch (e: com.google.firebase.FirebaseNetworkException) {
        throw RelayException(RelayException.Reason.NETWORK, e)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw RelayException(RelayException.Reason.OTHER, e)
    }

    companion object {
        private const val USERNAMES = "usernames"
        private const val USERS = "users"
        private const val ONE_TIME = "oneTimeKeys"
        private const val INBOX = "inbox"
        private const val MESSAGES = "messages"

        /**
         * Firebase يتطلب بريدًا لحسابات كلمة المرور. نستخدم عنوانًا صوريًا على نطاق محجوز
         * للأمثلة (RFC 2606)، فلا يُرسل إليه شيء ولا يُطلب من المستخدم بريد حقيقي.
         */
        fun emailFor(username: String) = "$username@users.example.com"

        private val USERNAME_RE = Regex("^[a-z0-9_]{3,20}$")
        fun normalizeUsername(raw: String) = raw.trim().lowercase()
        fun isValidUsername(u: String) = USERNAME_RE.matches(u)
    }
}
