package app.dugy.net

import android.content.Context
import app.dugy.data.ChatRepository
import app.dugy.data.MessageStatus
import app.dugy.data.SettingsStore
import app.dugy.e2e.DugySignalStore
import app.dugy.e2e.E2eEngine
import app.dugy.e2e.EncryptResult
import app.dugy.notify.Notifier
import app.dugy.security.AesGcm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors

data class AccountInfo(val uid: String, val username: String)

/** حالة الأمان لجهة اتصال كما تُعرض في الواجهة. */
data class ContactSecurity(
    val verified: Boolean,
    val keyChanged: Boolean,
    val knownIdentity: Boolean,
)

/**
 * يربط التشفير الطرفي (E2eEngine) بالخادم (ServerRelay) وبالمخزن المحلي للمحادثات.
 *
 * ترتيب الإرسال: نص ← تشفير على الجهاز ← طابور الصادر ← الخادم يمرّرها فورًا إلى المستلم.
 * الخادم لا يحفظ الرسائل؛ إن لم يكن المستلم متصلًا تبقى مشفّرة في طابور الصادر هنا
 * وتُرسل تلقائيًا حين يتصل. الاتصال بالخادم يبقى قائمًا والتطبيق مقفل أيضًا، فما يصل
 * يُحفظ مشفّرًا في طابور الوارد ويُفك عند فتح التطبيق.
 * حالة بروتوكول Signal تُحفظ في ملف مشفّر بمفتاح البيانات المحمي برمز القفل،
 * فلا يمكن الإرسال أو الاستقبال والتطبيق مقفل.
 */
class Messenger(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: ChatRepository,
    private val settings: SettingsStore,
) {
    val relay = ServerRelay(context)
    val connectivity = Connectivity(context)

    val isConfigured: Boolean get() = relay.isConfigured

    private val file = File(context.filesDir, "signal.bin")
    private val tmp = File(context.filesDir, "signal.bin.tmp")
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mutex = Mutex()

    @Volatile private var key: ByteArray? = null
    @Volatile private var store: DugySignalStore? = null
    private val outbox = Outbox(context)
    private val inboxQueue = Inbox(context)
    private val inboxLock = Mutex()

    /** صحيح ما دامت شاشة المحادثات المخفية معروضة، فلا داعي لتنبيه. */
    @Volatile var hiddenOpen: Boolean = false
    private val nameCache = HashMap<String, String>()

    private val _account = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    /** يزداد كلما تغيّرت حالة التوثيق أو المفاتيح، لتحديث الواجهة. */
    private val _securityVersion = MutableStateFlow(0)
    val securityVersion: StateFlow<Int> = _securityVersion.asStateFlow()

    private val _inboxError = MutableStateFlow(false)
    val inboxError: StateFlow<Boolean> = _inboxError.asStateFlow()

    // ---------------- فتح وإغلاق مع القفل ----------------

    init {
        startWatcher()
        scope.launch { connectivity.online.collect { if (it) relay.reconnectNow() } }
    }

    suspend fun open(dataKey: ByteArray) {
        val k = dataKey.copyOf()
        key = k
        if (!relay.isConfigured) return
        val uid = relay.currentUid
        val username = relay.currentUsername
        val loaded = withContext(io) { load(k) }
        if (uid != null && username != null && loaded != null && loaded.first == uid) {
            store = loaded.second
            _account.value = AccountInfo(uid, username)
            reconcileOutbox()
            relay.connect(link)
            scope.launch { processInbox() }
            scope.launch { replenishKeys() }
        } else if (uid != null) {
            // جلسة خادم بلا مفاتيح محلية مطابقة (مثلًا بعد مسح البيانات): نطلب الدخول من جديد.
            relay.signOut()
        }
        bump()
    }

    fun close() {
        store = null
        key?.fill(0)
        key = null
        _account.value = null
        nameCache.clear()
    }

    /** مسح كل شيء: المفاتيح المحلية وجلسة الخادم. */
    fun wipe() {
        close()
        Notifier.cancelAll(context)
        relay.signOut()
        outbox.clear()
        inboxQueue.clear()
        scope.launch(io) {
            file.delete()
            tmp.delete()
        }
    }

    // ---------------- الاتصال الحي ----------------

    /** يبقي الاتصال بالخادم قائمًا ما دام على الهاتف حساب، والتطبيق مقفل أو مفتوح. */
    fun startWatcher() {
        if (relay.isConfigured && relay.currentUid != null) relay.connect(link)
    }

    private val unlocked: Boolean get() = key != null && store != null

    // كسول: يُستخدم من init قبل موضعه في الملف.
    private val link: LinkListener by lazy { createLink() }

    private fun createLink() = object : LinkListener {
        override fun onConnected() {
            _inboxError.value = false
            flushOutbox(null)
        }

        override fun onDisconnected() {
            _inboxError.value = true
        }

        override fun onMessage(m: IncomingEnvelope) {
            // يُحفظ أولًا ثم يُؤكَّد الاستلام، فلا تضيع الرسالة إن أُغلق التطبيق فجأة.
            val fresh = inboxQueue.add(m)
            relay.ack(m.id)
            if (fresh && !hiddenOpen) Notifier.showKhawater(context)
            if (unlocked) scope.launch { processInbox() }
        }

        override fun onDelivered(id: String) = settle(id, OutItem.DELIVERED)

        override fun onOffline(id: String, toUid: String) = Unit

        override fun onOnline(uid: String) = flushOutbox(uid)

        override fun onUnknown(id: String) = settle(id, OutItem.FAILED)
    }

    private fun settle(id: String, state: Int) {
        val item = outbox.find(id) ?: return
        if (unlocked) {
            val status = if (state == OutItem.DELIVERED) MessageStatus.DELIVERED else MessageStatus.FAILED
            repository.updateStatus(item.conversationId, id, status)
            outbox.remove(id)
        } else {
            outbox.setState(id, state)
        }
    }

    /** يرسل ما في طابور الصادر (لمستلم واحد أو للجميع). المشفّر جاهز، فلا يلزم فتح التطبيق. */
    private fun flushOutbox(toUid: String?) {
        if (!relay.connected) return
        outbox.pending()
            .filter { toUid == null || it.toUid == toUid }
            .forEach { relay.send(it.toUid, it.id, it.envelope, it.sentAt) }
    }

    /** بعد فتح القفل: نحدّث حالة الرسائل التي سُلِّمت أو فشلت والتطبيق مقفل. */
    private fun reconcileOutbox() {
        outbox.items().filter { it.state != OutItem.PENDING }.forEach { item ->
            val status = if (item.state == OutItem.DELIVERED) MessageStatus.DELIVERED else MessageStatus.FAILED
            repository.updateStatus(item.conversationId, item.id, status)
            outbox.remove(item.id)
        }
    }

    // ---------------- الحساب ----------------

    /** يعيد null عند النجاح، أو رسالة خطأ بالعربية. */
    suspend fun createAccount(rawUsername: String, password: String, invite: String): String? {
        val username = ServerRelay.normalizeUsername(rawUsername)
        validate(username, password)?.let { return it }
        if (invite.isBlank()) return "اكتب رمز الدعوة."
        if (key == null) return "التطبيق مقفل."
        return try {
            val uid = relay.signUp(username, password, invite.trim())
            setUpDevice(uid, username)
            null
        } catch (e: RelayException) {
            describe(e)
        }
    }

    suspend fun signIn(rawUsername: String, password: String): String? {
        val username = ServerRelay.normalizeUsername(rawUsername)
        if (!ServerRelay.isValidUsername(username) || password.isEmpty()) return "اكتب اسم المستخدم وكلمة المرور."
        if (key == null) return "التطبيق مقفل."
        return try {
            val uid = relay.signIn(username, password)
            setUpDevice(uid, username)
            null
        } catch (e: RelayException) {
            describe(e)
        }
    }

    /**
     * كل جهاز يولّد مفتاح هوية جديدًا ولا يُنقل أي مفتاح خاص عبر الخادم. لذلك سيرى
     * من راسلك سابقًا تحذير «تغيّر المفتاح» عند تسجيل الدخول من جهاز جديد، وهذا مقصود.
     */
    private suspend fun setUpDevice(uid: String, username: String) {
        val s = DugySignalStore.create()
        val keys = E2eEngine.generateKeys(s, oneTimeCount = 100)
        relay.publishKeys(uid, username, keys)
        mutex.withLock { store = s }
        persist(uid)
        _account.value = AccountInfo(uid, username)
        relay.connect(link)
        bump()
    }

    fun signOut() {
        relay.signOut()
        outbox.clear()
        inboxQueue.clear()
        store = null
        _account.value = null
        scope.launch(io) {
            file.delete()
            tmp.delete()
        }
        bump()
    }

    /**
     * للتحقق من كلمة المرور عبر الخادم (للحسابات القديمة التي لم يُحفظ لها أثر محلي بعد).
     * يعيد true/false، أو null إذا تعذّر الاتصال.
     */
    suspend fun verifyPasswordOnline(username: String, password: String): Boolean? = try {
        relay.verifyPassword(username, password)
    } catch (e: RelayException) {
        null
    }

    private fun validate(username: String, password: String): String? = when {
        !ServerRelay.isValidUsername(username) ->
            "اسم المستخدم من 3 إلى 20 حرفًا: حروف إنجليزية صغيرة أو أرقام أو _ فقط."
        password.length < 8 -> "كلمة المرور 8 أحرف على الأقل."
        else -> null
    }

    private fun describe(e: RelayException): String = when (e.reason) {
        RelayException.Reason.USERNAME_TAKEN -> "اسم المستخدم محجوز. اختر اسمًا آخر."
        RelayException.Reason.WEAK_PASSWORD -> "كلمة المرور ضعيفة. اختر كلمة أطول."
        RelayException.Reason.WRONG_CREDENTIALS, RelayException.Reason.NO_SUCH_USER ->
            "اسم المستخدم أو كلمة المرور غير صحيحة."
        RelayException.Reason.BAD_INVITE -> "رمز الدعوة غير صحيح أو انتهت صلاحيته."
        RelayException.Reason.RATE_LIMITED -> "محاولات كثيرة. انتظر ربع ساعة ثم حاول مجددًا."
        RelayException.Reason.UNAUTHORIZED -> "انتهت الجلسة. سجّل الدخول من جديد."
        RelayException.Reason.NETWORK -> "لا يوجد اتصال بالخادم. تحقق من الإنترنت وتشغيل Tailscale."
        RelayException.Reason.OTHER -> "تعذّر الاتصال بالخادم. حاول مجددًا لاحقًا."
    }

    // ---------------- بدء محادثة ----------------

    /** يبحث عن اسم المستخدم على الخادم ويفتح محادثة معه. يعيد معرّف المحادثة أو رسالة خطأ. */
    suspend fun startConversation(rawUsername: String): Result<String> {
        val acct = _account.value ?: return Result.failure(IllegalStateException("سجّل الدخول أولًا."))
        val username = ServerRelay.normalizeUsername(rawUsername)
        if (!ServerRelay.isValidUsername(username)) {
            return Result.failure(IllegalArgumentException("اسم مستخدم غير صالح."))
        }
        if (username == acct.username) return Result.failure(IllegalArgumentException("هذا اسم حسابك أنت."))
        return try {
            val uid = relay.lookupUid(username)
                ?: return Result.failure(IllegalArgumentException("لا يوجد مستخدم بهذا الاسم."))
            nameCache[username] = uid
            val id = repository.findOrCreateConversation(username, uid)
                ?: return Result.failure(IllegalStateException("التطبيق مقفل."))
            Result.success(id)
        } catch (e: RelayException) {
            Result.failure(IllegalStateException(describe(e)))
        }
    }

    // ---------------- الإرسال ----------------

    fun send(conversationId: String, text: String) {
        val msg = repository.addOutgoing(conversationId, text) ?: return
        scope.launch { deliver(conversationId, msg.id, text) }
    }

    fun retry(conversationId: String, messageId: String) {
        val msg = repository.conversation(conversationId)?.messages?.firstOrNull { it.id == messageId } ?: return
        if (!msg.outgoing) return
        repository.updateStatus(conversationId, messageId, MessageStatus.PENDING)
        scope.launch { deliver(conversationId, messageId, msg.text) }
    }

    private suspend fun deliver(conversationId: String, messageId: String, text: String) {
        val status = try {
            deliverOrStatus(conversationId, messageId, text)
        } catch (e: RelayException) {
            MessageStatus.FAILED
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            MessageStatus.FAILED
        }
        repository.updateStatus(conversationId, messageId, status)
    }

    private suspend fun deliverOrStatus(conversationId: String, messageId: String, text: String): MessageStatus {
        val acct = _account.value ?: return MessageStatus.FAILED
        val s = store ?: return MessageStatus.FAILED
        val contact = repository.conversation(conversationId)?.contact ?: return MessageStatus.FAILED
        val name = contact.handle
        // نسأل الخادم دائمًا عن المعرّف، فالمعرّفات المحفوظة من خادم سابق لا تصلح.
        val uid = nameCache[name] ?: relay.lookupUid(name)?.also { nameCache[name] = it } ?: return MessageStatus.FAILED

        val published = relay.publishedIdentity(uid)
        val bundle = if (!s.containsSession(E2eEngine.address(name))) relay.fetchBundle(uid) else null

        // التشفير يتم هنا على الجهاز، قبل أي إرسال.
        val result = mutex.withLock {
            E2eEngine.encrypt(s, acct.username, name, text, published) { bundle }
        }
        persist(acct.uid)

        return when (result) {
            is EncryptResult.Ok -> {
                val item = OutItem(messageId, conversationId, uid, System.currentTimeMillis(), result.envelope)
                outbox.add(item)
                relay.send(item.toUid, item.id, item.envelope, item.sentAt)
                MessageStatus.PENDING
            }
            EncryptResult.KeyChanged -> {
                bump()
                MessageStatus.KEY_CHANGED
            }
            EncryptResult.NoKeys -> MessageStatus.FAILED
        }
    }

    // ---------------- الاستقبال ----------------

    /** يفك ما وصل إلى طابور الوارد ويضيفه إلى المحادثات. يتطلب أن يكون التطبيق مفتوحًا. */
    private suspend fun processInbox() = inboxLock.withLock {
        val acct = _account.value ?: return@withLock
        for (m in inboxQueue.items().toList()) {
            val s = store ?: return@withLock
            if (m.fromUid == acct.uid || m.fromName.isEmpty()) {
                inboxQueue.remove(m)
                continue
            }
            nameCache[m.fromName] = m.fromUid
            val text = mutex.withLock {
                runCatching { E2eEngine.decrypt(s, acct.username, m.fromName, m.envelope) }.getOrNull()
            }
            persist(acct.uid)
            val added = repository.addIncoming(
                username = m.fromName,
                uid = m.fromUid,
                messageId = m.id,
                text = text ?: "تعذّرت قراءة رسالة وصلت من هذه الجهة. قد تكون أُرسلت إلى جهاز سابق لك.",
                at = m.sentAt,
                failed = text == null,
            )
            // إن أُقفل التطبيق أثناء المعالجة تبقى الرسالة في الطابور لفتحها لاحقًا.
            if (added == null) return@withLock
            inboxQueue.remove(m)
        }
        bump()
    }

    private suspend fun replenishKeys() {
        val acct = _account.value ?: return
        val s = store ?: return
        runCatching {
            if (relay.oneTimeKeyCount(acct.uid) < 20) {
                val fresh = mutex.withLock { E2eEngine.generateOneTimePreKeys(s, 100) }
                persist(acct.uid)
                relay.uploadOneTimeKeys(acct.uid, fresh)
            }
        }
    }

    // ---------------- التوثيق وتغيّر المفاتيح ----------------

    fun security(username: String): ContactSecurity {
        val s = store ?: return ContactSecurity(verified = false, keyChanged = false, knownIdentity = false)
        return ContactSecurity(
            verified = s.isVerified(username),
            keyChanged = s.hasKeyChangeWarning(username),
            knownIdentity = s.identityOf(username) != null,
        )
    }

    /**
     * رمز الأمان للمقارنة مع جهة الاتصال. إن لم نراسلها بعد نستخدم مفتاحها المنشور
     * ونحفظه (الثقة عند أول استخدام)، ويبقى غير موثّق حتى تطابقا الرمز.
     */
    suspend fun safetyNumber(username: String, uid: String): String? {
        val acct = _account.value ?: return null
        val s = store ?: return null
        var remote = s.identityOf(username)
        if (remote == null) {
            val resolvedUid = nameCache[username] ?: relay.lookupUid(username) ?: return null
            val published = relay.publishedIdentity(resolvedUid) ?: return null
            mutex.withLock {
                s.saveIdentity(E2eEngine.address(username), org.signal.libsignal.protocol.IdentityKey(published))
            }
            persist(acct.uid)
            remote = s.identityOf(username) ?: return null
        }
        return E2eEngine.safetyNumber(acct.username, s.publicIdentity, username, remote)
    }

    suspend fun setVerified(username: String, verified: Boolean) {
        val acct = _account.value ?: return
        mutex.withLock { store?.setVerified(username, verified) }
        persist(acct.uid)
        bump()
    }

    /** قبول المفتاح الجديد بعد تحذير المستخدم؛ تُعاد الرسائل المعلّقة بسببه. */
    suspend fun acceptNewKey(conversationId: String, username: String) {
        val acct = _account.value ?: return
        mutex.withLock {
            store?.acceptPendingIdentity(username)
            store?.dismissKeyChangeNotice(username)
        }
        persist(acct.uid)
        bump()
        repository.conversation(conversationId)?.messages
            ?.filter { it.outgoing && it.status == MessageStatus.KEY_CHANGED }
            ?.forEach { retry(conversationId, it.id) }
    }

    // ---------------- الحفظ المشفّر ----------------

    private suspend fun persist(uid: String) {
        val k = key?.copyOf() ?: return
        val bytes = mutex.withLock { store?.serialize() } ?: return
        withContext(io) {
            try {
                val payload = ByteArrayOutputStream().also { out ->
                    DataOutputStream(out).use {
                        it.writeUTF(uid)
                        it.writeInt(bytes.size)
                        it.write(bytes)
                    }
                }.toByteArray()
                tmp.writeBytes(AesGcm.encrypt(k, payload, AAD))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } finally {
                k.fill(0)
            }
        }
    }

    private fun load(k: ByteArray): Pair<String, DugySignalStore>? {
        if (!file.exists()) return null
        return try {
            val payload = AesGcm.decrypt(k, file.readBytes(), AAD)
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val uid = input.readUTF()
                val size = input.readInt()
                val bytes = ByteArray(size).also { input.readFully(it) }
                uid to DugySignalStore.deserialize(bytes)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun bump() = _securityVersion.update { it + 1 }

    private companion object {
        val AAD = "dugy-signal-v1".toByteArray()
    }
}
