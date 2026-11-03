package app.dugy.e2e

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * مخزن حالة بروتوكول Signal لهذا الجهاز: مفتاح الهوية، والمفاتيح المسبقة، والجلسات،
 * ومفاتيح هويات جهات الاتصال وحالة التحقق منها.
 *
 * لا يحتوي على أي استدعاء لأندرويد، فيُختبر مباشرة على JVM.
 * يُحوَّل كاملًا إلى بايتات عبر [serialize]، ويتولى التطبيق تشفيرها بمفتاح البيانات
 * المحمي برمز القفل قبل حفظها على القرص. لا يُرفع أي جزء منه إلى الخادم.
 *
 * سياسة الثقة بالهويات:
 *  - أول مفتاح نراه لجهة اتصال يُحفظ (الثقة عند أول استخدام) دون أن يُعدّ «موثّقًا».
 *  - إذا تغيّر مفتاح جهة اتصال يُرفض الإرسال إليها حتى يقبل المستخدم المفتاح الجديد صراحةً،
 *    وتُلغى حالة التوثيق السابقة.
 */
class DugySignalStore private constructor(
    private val identity: IdentityKeyPair,
    private val registrationId: Int,
) : SignalProtocolStore {

    private val identities = HashMap<String, ByteArray>()
    private val verified = HashSet<String>()
    /** جهات اتصال تغيّر مفتاحها؛ يُعرض للمستخدم تحذير حتى يطّلع عليه. */
    private val changeNotices = HashSet<String>()
    /** مفاتيح جديدة رُفضت لأنها تختلف عن المحفوظة؛ تنتظر قبول المستخدم. */
    private val pendingIdentities = HashMap<String, ByteArray>()
    private val sessions = HashMap<String, ByteArray>()
    private val preKeys = HashMap<Int, ByteArray>()
    private val signedPreKeys = HashMap<Int, ByteArray>()
    private val kyberPreKeys = HashMap<Int, ByteArray>()
    private val kyberBaseKeysSeen = HashMap<String, HashSet<String>>()
    private val senderKeys = HashMap<String, ByteArray>()

    /** عدّاد معرّفات المفاتيح المسبقة حتى لا يتكرر معرّف بعد إعادة التوليد. */
    var nextPreKeyId: Int = 1
        internal set
    var nextSignedPreKeyId: Int = 1
        internal set

    val publicIdentity: IdentityKey get() = identity.publicKey

    // ---------------- IdentityKeyStore ----------------

    override fun getIdentityKeyPair(): IdentityKeyPair = identity

    override fun getLocalRegistrationId(): Int = registrationId

    @Synchronized
    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        val name = address.name
        val existing = identities[name]
        val incoming = identityKey.serialize()
        identities[name] = incoming
        pendingIdentities.remove(name)
        return if (existing == null || existing.contentEquals(incoming)) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            verified.remove(name)
            changeNotices += name
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    @Synchronized
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val name = address.name
        val existing = identities[name] ?: return true
        val incoming = identityKey.serialize()
        if (existing.contentEquals(incoming)) return true
        // الاستقبال: نقبل الرسالة كي لا تضيع، ويحفظ saveIdentity المفتاح الجديد ويسجّل تحذيرًا.
        if (direction == IdentityKeyStore.Direction.RECEIVING) return true
        // الإرسال: نرفض حتى يقبل المستخدم المفتاح الجديد صراحةً.
        pendingIdentities[name] = incoming
        verified.remove(name)
        return false
    }

    @Synchronized
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        identities[address.name]?.let { IdentityKey(it) }

    // ---------------- حالة التحقق وتغيّر المفتاح ----------------

    @Synchronized
    fun identityOf(name: String): IdentityKey? = identities[name]?.let { IdentityKey(it) }

    @Synchronized
    fun isVerified(name: String): Boolean = name in verified

    @Synchronized
    fun setVerified(name: String, value: Boolean) {
        if (value && identities.containsKey(name)) verified += name else verified -= name
    }

    @Synchronized
    fun hasPendingIdentityChange(name: String): Boolean = pendingIdentities.containsKey(name)

    /**
     * يسجّل مفتاحًا منشورًا على الخادم يختلف عن المحفوظ (مثلًا بعد أن أعادت جهة الاتصال
     * تثبيت التطبيق). يعيد true إذا كان مختلفًا فعلًا، فيُمنع الإرسال حتى يقبله المستخدم.
     */
    @Synchronized
    fun notePublishedIdentity(name: String, published: ByteArray): Boolean {
        val existing = identities[name] ?: return false
        if (existing.contentEquals(published)) return false
        pendingIdentities[name] = published
        verified.remove(name)
        return true
    }

    /** هل تغيّر مفتاح جهة الاتصال ولم يطّلع المستخدم على التحذير بعد؟ */
    @Synchronized
    fun hasKeyChangeWarning(name: String): Boolean = name in changeNotices || pendingIdentities.containsKey(name)

    @Synchronized
    fun dismissKeyChangeNotice(name: String) {
        changeNotices -= name
    }

    @Synchronized
    fun pendingIdentity(name: String): IdentityKey? = pendingIdentities[name]?.let { IdentityKey(it) }

    /**
     * قبول المفتاح الجديد بعد أن أكّد المستخدم ذلك: تُحذف الجلسة القديمة وتبدأ جلسة جديدة
     * مع المفتاح الجديد عند الإرسال التالي. لا يُعدّ المفتاح الجديد موثّقًا.
     */
    @Synchronized
    fun acceptPendingIdentity(name: String) {
        val pending = pendingIdentities.remove(name) ?: return
        identities[name] = pending
        verified -= name
        changeNotices -= name
        sessions.keys.removeAll { it.startsWith("$name\u0000") }
    }

    // ---------------- SessionStore ----------------

    private fun key(a: SignalProtocolAddress) = a.name + "\u0000" + a.deviceId

    @Synchronized
    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        sessions[key(address)]?.let { SessionRecord(it) } ?: SessionRecord()

    @Synchronized
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { a ->
            sessions[key(a)]?.let { SessionRecord(it) } ?: throw NoSessionException("no session for $a")
        }

    @Synchronized
    override fun getSubDeviceSessions(name: String): List<Int> =
        sessions.keys.filter { it.startsWith("$name\u0000") }
            .map { it.substringAfter('\u0000').toInt() }
            .filter { it != 1 }

    @Synchronized
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessions[key(address)] = record.serialize()
    }

    @Synchronized
    override fun containsSession(address: SignalProtocolAddress): Boolean =
        sessions.containsKey(key(address))

    @Synchronized
    override fun deleteSession(address: SignalProtocolAddress) {
        sessions.remove(key(address))
    }

    @Synchronized
    override fun deleteAllSessions(name: String) {
        sessions.keys.removeAll { it.startsWith("$name\u0000") }
    }

    // ---------------- PreKeyStore ----------------

    @Synchronized
    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        preKeys[preKeyId]?.let { PreKeyRecord(it) } ?: throw InvalidKeyIdException("no prekey $preKeyId")

    @Synchronized
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        preKeys[preKeyId] = record.serialize()
    }

    @Synchronized
    override fun containsPreKey(preKeyId: Int): Boolean = preKeys.containsKey(preKeyId)

    @Synchronized
    override fun removePreKey(preKeyId: Int) {
        preKeys.remove(preKeyId)
    }

    @Synchronized
    fun preKeyCount(): Int = preKeys.size

    // ---------------- SignedPreKeyStore ----------------

    @Synchronized
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        signedPreKeys[signedPreKeyId]?.let { SignedPreKeyRecord(it) }
            ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")

    @Synchronized
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = signedPreKeys.values.map { SignedPreKeyRecord(it) }

    @Synchronized
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signedPreKeys[signedPreKeyId] = record.serialize()
    }

    @Synchronized
    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = signedPreKeys.containsKey(signedPreKeyId)

    @Synchronized
    override fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeys.remove(signedPreKeyId)
    }

    // ---------------- KyberPreKeyStore ----------------

    @Synchronized
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        kyberPreKeys[kyberPreKeyId]?.let { KyberPreKeyRecord(it) }
            ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")

    @Synchronized
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = kyberPreKeys.values.map { KyberPreKeyRecord(it) }

    @Synchronized
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        kyberPreKeys[kyberPreKeyId] = record.serialize()
    }

    @Synchronized
    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = kyberPreKeys.containsKey(kyberPreKeyId)

    /**
     * مفتاح Kyber هنا «مفتاح أخير» يُعاد استخدامه، فنرفض تكرار المفتاح الأساسي نفسه
     * لمنع إعادة تشغيل رسالة بدء الجلسة.
     */
    @Synchronized
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val seen = kyberBaseKeysSeen.getOrPut("$kyberPreKeyId:$signedPreKeyId") { HashSet() }
        val encoded = java.util.Base64.getEncoder().encodeToString(baseKey.serialize())
        if (!seen.add(encoded)) throw ReusedBaseKeyException()
    }

    // ---------------- SenderKeyStore (للمجموعات؛ غير مستخدم حاليًا) ----------------

    @Synchronized
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        senderKeys[key(sender) + "\u0000" + distributionId] = record.serialize()
    }

    @Synchronized
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        senderKeys[key(sender) + "\u0000" + distributionId]?.let { SenderKeyRecord(it) }

    // ---------------- الحفظ والاستعادة ----------------

    @Synchronized
    fun serialize(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(FORMAT)
            writeBytes(out, identity.serialize())
            out.writeInt(registrationId)
            out.writeInt(nextPreKeyId)
            out.writeInt(nextSignedPreKeyId)
            writeStringMap(out, identities)
            writeStringMap(out, pendingIdentities)
            out.writeInt(verified.size)
            verified.forEach { out.writeUTF(it) }
            out.writeInt(changeNotices.size)
            changeNotices.forEach { out.writeUTF(it) }
            writeStringMap(out, sessions)
            writeIntMap(out, preKeys)
            writeIntMap(out, signedPreKeys)
            writeIntMap(out, kyberPreKeys)
            out.writeInt(kyberBaseKeysSeen.size)
            kyberBaseKeysSeen.forEach { (k, set) ->
                out.writeUTF(k)
                out.writeInt(set.size)
                set.forEach { out.writeUTF(it) }
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val FORMAT = 1

        fun create(): DugySignalStore {
            val registrationId = java.security.SecureRandom().nextInt(16380) + 1
            return DugySignalStore(IdentityKeyPair.generate(), registrationId)
        }

        fun deserialize(data: ByteArray): DugySignalStore {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                require(input.readInt() == FORMAT) { "unknown store format" }
                val identity = IdentityKeyPair(readBytes(input))
                val store = DugySignalStore(identity, input.readInt())
                store.nextPreKeyId = input.readInt()
                store.nextSignedPreKeyId = input.readInt()
                readStringMap(input, store.identities)
                readStringMap(input, store.pendingIdentities)
                repeat(input.readInt()) { store.verified += input.readUTF() }
                repeat(input.readInt()) { store.changeNotices += input.readUTF() }
                readStringMap(input, store.sessions)
                readIntMap(input, store.preKeys)
                readIntMap(input, store.signedPreKeys)
                readIntMap(input, store.kyberPreKeys)
                repeat(input.readInt()) {
                    val k = input.readUTF()
                    val set = HashSet<String>()
                    repeat(input.readInt()) { set += input.readUTF() }
                    store.kyberBaseKeysSeen[k] = set
                }
                return store
            }
        }

        private fun writeBytes(out: DataOutputStream, b: ByteArray) {
            out.writeInt(b.size)
            out.write(b)
        }

        private fun readBytes(input: DataInputStream): ByteArray {
            val size = input.readInt()
            require(size in 0..(1 shl 22)) { "corrupt store" }
            return ByteArray(size).also { input.readFully(it) }
        }

        private fun writeStringMap(out: DataOutputStream, map: Map<String, ByteArray>) {
            out.writeInt(map.size)
            map.forEach { (k, v) ->
                out.writeUTF(k)
                writeBytes(out, v)
            }
        }

        private fun readStringMap(input: DataInputStream, into: MutableMap<String, ByteArray>) {
            repeat(input.readInt()) { into[input.readUTF()] = readBytes(input) }
        }

        private fun writeIntMap(out: DataOutputStream, map: Map<Int, ByteArray>) {
            out.writeInt(map.size)
            map.forEach { (k, v) ->
                out.writeInt(k)
                writeBytes(out, v)
            }
        }

        private fun readIntMap(input: DataInputStream, into: MutableMap<Int, ByteArray>) {
            repeat(input.readInt()) { into[input.readInt()] = readBytes(input) }
        }
    }
}
