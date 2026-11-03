package app.dugy.e2e

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/** مفتاح عام لمرة واحدة كما يُنشر على الخادم. */
class PublicPreKey(val id: Int, val publicKey: ByteArray)

/**
 * ما يُرفع إلى الخادم عند إنشاء الحساب: مفاتيح **عامة** فقط وتواقيعها.
 * المفاتيح الخاصة تبقى في [DugySignalStore] على الجهاز.
 */
class PublishedKeys(
    val registrationId: Int,
    val identityKey: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val kyberPreKeyId: Int,
    val kyberPreKey: ByteArray,
    val kyberPreKeySignature: ByteArray,
    val oneTimePreKeys: List<PublicPreKey>,
)

/** حزمة مفاتيح جهة اتصال كما يعيدها الخادم، مع مفتاح لمرة واحدة إن بقي منها شيء. */
class RemoteBundle(
    val registrationId: Int,
    val identityKey: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val kyberPreKeyId: Int,
    val kyberPreKey: ByteArray,
    val kyberPreKeySignature: ByteArray,
    val oneTimePreKey: PublicPreKey?,
)

/** الرسالة المشفّرة كما تُرسل عبر الخادم. لا تحتوي على النص الصريح. */
class Envelope(val type: Int, val body: ByteArray)

sealed interface EncryptResult {
    class Ok(val envelope: Envelope) : EncryptResult
    /** تغيّر مفتاح هوية جهة الاتصال؛ لا يُرسل شيء حتى يقبل المستخدم المفتاح الجديد. */
    data object KeyChanged : EncryptResult
    /** لا توجد جلسة ولم نحصل على مفاتيح جهة الاتصال من الخادم. */
    data object NoKeys : EncryptResult
}

/**
 * التشفير الطرفي باستخدام مكتبة libsignal الرسمية (بروتوكول Signal: PQXDH + Double Ratchet).
 * هذا الملف لا يخترع أي تشفير؛ هو يربط واجهات المكتبة بتطبيقنا فقط.
 */
object E2eEngine {
    const val DEVICE_ID = 1
    private const val PAD_BLOCK = 160
    private const val FINGERPRINT_ITERATIONS = 5200
    private const val FINGERPRINT_VERSION = 1

    fun address(name: String) = SignalProtocolAddress(name, DEVICE_ID)

    // ---------------- توليد المفاتيح ----------------

    /** يولّد المفتاح الموقّع ومفتاح Kyber الأخير ومفاتيح لمرة واحدة، ويعيد أجزاءها العامة للنشر. */
    fun generateKeys(store: DugySignalStore, oneTimeCount: Int = 50): PublishedKeys {
        val identity = store.getIdentityKeyPair()
        val now = System.currentTimeMillis()

        val signedId = store.nextSignedPreKeyId
        val signedPair = ECKeyPair.generate()
        val signedSig = identity.privateKey.calculateSignature(signedPair.publicKey.serialize())
        store.storeSignedPreKey(signedId, SignedPreKeyRecord(signedId, now, signedPair, signedSig))

        val kyberId = signedId
        val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSig = identity.privateKey.calculateSignature(kyberPair.publicKey.serialize())
        store.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, now, kyberPair, kyberSig))
        store.nextSignedPreKeyId = signedId + 1

        return PublishedKeys(
            registrationId = store.getLocalRegistrationId(),
            identityKey = identity.publicKey.serialize(),
            signedPreKeyId = signedId,
            signedPreKey = signedPair.publicKey.serialize(),
            signedPreKeySignature = signedSig,
            kyberPreKeyId = kyberId,
            kyberPreKey = kyberPair.publicKey.serialize(),
            kyberPreKeySignature = kyberSig,
            oneTimePreKeys = generateOneTimePreKeys(store, oneTimeCount),
        )
    }

    fun generateOneTimePreKeys(store: DugySignalStore, count: Int): List<PublicPreKey> {
        val result = ArrayList<PublicPreKey>(count)
        repeat(count) {
            val id = store.nextPreKeyId
            store.nextPreKeyId = if (id >= 0xFFFFFF) 1 else id + 1
            val pair = ECKeyPair.generate()
            store.storePreKey(id, PreKeyRecord(id, pair))
            result += PublicPreKey(id, pair.publicKey.serialize())
        }
        return result
    }

    // ---------------- الإرسال ----------------

    /**
     * يشفّر [plaintext] لجهة الاتصال [remoteName] على هذا الجهاز قبل أي اتصال شبكي.
     *
     * @param publishedIdentity مفتاح الهوية المنشور حاليًا على الخادم لجهة الاتصال (إن توفر)،
     *   لاكتشاف إعادة تسجيلها بمفتاح جديد.
     * @param fetchBundle يُستدعى فقط إذا لم توجد جلسة بعد.
     */
    fun encrypt(
        store: DugySignalStore,
        localName: String,
        remoteName: String,
        plaintext: String,
        publishedIdentity: ByteArray?,
        fetchBundle: () -> RemoteBundle?,
    ): EncryptResult {
        val remote = address(remoteName)
        val local = address(localName)

        if (publishedIdentity != null) store.notePublishedIdentity(remoteName, publishedIdentity)
        if (store.hasPendingIdentityChange(remoteName)) return EncryptResult.KeyChanged

        if (!store.containsSession(remote)) {
            val b = fetchBundle() ?: return EncryptResult.NoKeys
            val bundle = PreKeyBundle(
                b.registrationId,
                DEVICE_ID,
                b.oneTimePreKey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
                b.oneTimePreKey?.let { ECPublicKey(it.publicKey) },
                b.signedPreKeyId,
                ECPublicKey(b.signedPreKey),
                b.signedPreKeySignature,
                IdentityKey(b.identityKey),
                b.kyberPreKeyId,
                KEMPublicKey(b.kyberPreKey),
                b.kyberPreKeySignature,
            )
            try {
                // تتحقق المكتبة هنا من توقيع المفاتيح بمفتاح الهوية قبل بدء الجلسة.
                SessionBuilder(store, remote, local).process(bundle)
            } catch (e: UntrustedIdentityException) {
                return EncryptResult.KeyChanged
            }
        }

        return try {
            val message = SessionCipher(store, local, remote).encrypt(pad(plaintext.toByteArray(Charsets.UTF_8)))
            EncryptResult.Ok(Envelope(message.type, message.serialize()))
        } catch (e: UntrustedIdentityException) {
            EncryptResult.KeyChanged
        }
    }

    // ---------------- الاستقبال ----------------

    /** يفك رسالة واردة. يرمي استثناءً إذا لم تكن موجّهة لهذا الجهاز أو عُدّلت أو تكررت. */
    fun decrypt(store: DugySignalStore, localName: String, remoteName: String, envelope: Envelope): String {
        val cipher = SessionCipher(store, address(localName), address(remoteName))
        val padded = when (envelope.type) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(envelope.body))
            CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(envelope.body))
            else -> throw IllegalArgumentException("unsupported message type ${envelope.type}")
        }
        return String(unpad(padded), Charsets.UTF_8)
    }

    // ---------------- رمز الأمان ----------------

    /**
     * رقم أمان من 60 خانة يطابقه الطرفان بالمقارنة المباشرة. يتطابق على الجهازين فقط إذا
     * كان كل طرف يملك مفتاح الهوية الحقيقي للآخر.
     */
    fun safetyNumber(localName: String, localKey: IdentityKey, remoteName: String, remoteKey: IdentityKey): String {
        val fingerprint = NumericFingerprintGenerator(FINGERPRINT_ITERATIONS).createFor(
            FINGERPRINT_VERSION,
            localName.toByteArray(Charsets.UTF_8),
            localKey,
            remoteName.toByteArray(Charsets.UTF_8),
            remoteKey,
        )
        return fingerprint.displayableFingerprint.displayText
    }

    fun formatSafetyNumber(digits: String): List<String> = digits.chunked(5)

    // ---------------- الحشو ----------------

    /** حشو بطول ثابت المضاعفات حتى لا يكشف حجم الرسالة المشفّرة طولها الدقيق. */
    fun pad(data: ByteArray): ByteArray {
        val size = ((data.size + 1 + PAD_BLOCK - 1) / PAD_BLOCK) * PAD_BLOCK
        val out = ByteArray(size)
        data.copyInto(out)
        out[data.size] = 0x80.toByte()
        return out
    }

    fun unpad(data: ByteArray): ByteArray {
        var i = data.size - 1
        while (i >= 0 && data[i] == 0.toByte()) i--
        require(i >= 0 && data[i] == 0x80.toByte()) { "bad padding" }
        return data.copyOf(i)
    }
}
