package app.dugy.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

sealed interface UnlockResult {
    data object Success : UnlockResult

    /**
     * @param freeAttemptsLeft عدد المحاولات المتبقية قبل بدء التأخير (null إذا بدأ التأخير).
     * @param lockoutMs مدة الانتظار المفروضة الآن (0 إن لم يوجد).
     */
    data class WrongPin(
        val freeAttemptsLeft: Int?,
        val lockoutMs: Long,
    ) : UnlockResult

    data class LockedOut(val remainingMs: Long) : UnlockResult

    /** بلغت المحاولات الفاشلة الحد (إلزامي)؛ على المستدعي تنفيذ المسح. */
    data object MustWipe : UnlockResult

    /** تعذّر الوصول إلى مفتاح Keystore (مثلًا بعد استعادة غير مدعومة). */
    data object StorageError : UnlockResult
}

/**
 * يدير «مفتاح البيانات» العشوائي الذي يشفّر الرسائل المحلية.
 *
 * التغليف بطبقتين:
 *   مفتاح البيانات ← AES-GCM بمفتاح مشتق من رمز القفل (PBKDF2) ← AES-GCM بمفتاح Keystore.
 * نتيجة ذلك:
 *   - لا يمكن فك الرسائل دون رمز القفل الصحيح (أو البصمة إذا فُعّلت).
 *   - لا يمكن تخمين الرمز خارج هذا الجهاز لأن الطبقة الخارجية مرتبطة بـ Keystore.
 *   - نسيان الرمز يعني فقدان البيانات المحلية؛ لا يوجد رمز احتياطي على أي خادم.
 */
class Vault(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Volatile
    private var dataKey: ByteArray? = null

    private val _biometricEnabled = MutableStateFlow(prefs.contains(K_BIO_BLOB))
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    val isSetUp: Boolean get() = prefs.contains(K_WRAPPED)
    val isUnlocked: Boolean get() = dataKey != null

    fun currentKey(): ByteArray = dataKey ?: throw IllegalStateException("vault is locked")

    // ---------------- الإعداد ورمز القفل ----------------

    /** إعداد أول مرة. عملية ثقيلة (PBKDF2): استدعِها خارج الخيط الرئيسي. */
    fun setup(pin: CharArray) {
        val key = ByteArray(32)
        random.nextBytes(key)
        storePinWrapping(key, pin)
        dataKey = key
        resetFailures()
    }

    private fun storePinWrapping(key: ByteArray, pin: CharArray) {
        val salt = ByteArray(16)
        random.nextBytes(salt)
        val kek = PinKdf.derive(pin, salt, PinKdf.ITERATIONS)
        val inner = try {
            AesGcm.encrypt(kek, key, AAD_PIN)
        } finally {
            kek.fill(0)
        }
        val outer = KeystoreCipher.encrypt(appContext, inner)
        prefs.edit()
            .putString(K_SALT, b64(salt))
            .putInt(K_ITER, PinKdf.ITERATIONS)
            .putString(K_WRAPPED, b64(outer))
            .commit()
    }

    /** يعيد مفتاح البيانات أو يرمي AEADBadTagException عند رمز خاطئ. */
    private fun decryptWithPin(pin: CharArray): ByteArray {
        val salt = unb64(prefs.getString(K_SALT, null) ?: throw IllegalStateException("not set up"))
        val iterations = prefs.getInt(K_ITER, PinKdf.ITERATIONS)
        val outer = unb64(prefs.getString(K_WRAPPED, null) ?: throw IllegalStateException("not set up"))
        val inner = KeystoreCipher.decrypt(outer)
        val kek = PinKdf.derive(pin, salt, iterations)
        try {
            return AesGcm.decrypt(kek, inner, AAD_PIN)
        } finally {
            kek.fill(0)
        }
    }

    /** عملية ثقيلة: استدعِها خارج الخيط الرئيسي. */
    fun unlockWithPin(pin: CharArray): UnlockResult {
        val remaining = lockoutRemainingMs()
        if (remaining > 0) return UnlockResult.LockedOut(remaining)

        val key = try {
            decryptWithPin(pin)
        } catch (e: AEADBadTagException) {
            null
        } catch (e: KeyMissingException) {
            return UnlockResult.StorageError
        } catch (e: IllegalStateException) {
            return UnlockResult.StorageError
        } catch (e: java.security.GeneralSecurityException) {
            return UnlockResult.StorageError
        }

        if (key == null) return registerFailure()

        dataKey = key
        resetFailures()
        return UnlockResult.Success
    }

    /** يتطلب أن يكون الخزنة مفتوحة. يعيد false إذا كان الرمز الحالي خاطئًا. */
    fun changePin(current: CharArray, newPin: CharArray): Boolean {
        val verified = try {
            decryptWithPin(current)
        } catch (e: AEADBadTagException) {
            return false
        }
        verified.fill(0)
        storePinWrapping(currentKey(), newPin)
        return true
    }

    // ---------------- محاولات فاشلة وتأخير تصاعدي ----------------

    fun failureCount(): Int = prefs.getInt(K_FAILS, 0)

    fun lockoutRemainingMs(): Long {
        val until = prefs.getLong(K_LOCK_UNTIL, 0L)
        val now = System.currentTimeMillis()
        // إذا أُعيدت ساعة الجهاز إلى الخلف بشكل كبير، لا نسمح بتأخير أطول من الحد الأقصى.
        return (until - now).coerceIn(0L, MAX_LOCKOUT_MS)
    }

    private fun registerFailure(): UnlockResult {
        val fails = failureCount() + 1
        if (fails >= WIPE_THRESHOLD) return UnlockResult.MustWipe

        val lockout = lockoutFor(fails)
        prefs.edit()
            .putInt(K_FAILS, fails)
            .putLong(K_LOCK_UNTIL, if (lockout > 0) System.currentTimeMillis() + lockout else 0L)
            .commit()

        return UnlockResult.WrongPin(
            freeAttemptsLeft = (FREE_ATTEMPTS - fails).takeIf { it > 0 },
            lockoutMs = lockout,
        )
    }

    private fun lockoutFor(fails: Int): Long {
        if (fails < FREE_ATTEMPTS) return 0L
        val steps = (fails - FREE_ATTEMPTS).coerceAtMost(10)
        return (BASE_LOCKOUT_MS shl steps).coerceAtMost(MAX_LOCKOUT_MS)
    }

    private fun resetFailures() {
        prefs.edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0L).commit()
    }

    // ---------------- كلمة مرور الدخول من «تواصل معنا» ----------------

    /**
     * يحفظ أثرًا لكلمة مرور الحساب (PBKDF2 بملح عشوائي) مغلّفًا بمفتاح البيانات،
     * ليُتحقق منها دون اتصال بالإنترنت. يتطلب أن تكون الخزنة مفتوحة. عملية ثقيلة.
     */
    fun setAccessSecret(password: CharArray) {
        val salt = ByteArray(16)
        random.nextBytes(salt)
        val hash = PinKdf.derive(password, salt, ACCESS_ITERATIONS)
        val blob = AesGcm.encrypt(currentKey(), salt + hash, AAD_ACCESS)
        hash.fill(0)
        prefs.edit().putString(K_ACCESS, b64(blob)).putInt(K_HIDDEN_FAILS, 0).commit()
    }

    fun hasAccessSecret(): Boolean = prefs.contains(K_ACCESS)

    fun clearAccessSecret() {
        prefs.edit().remove(K_ACCESS).putInt(K_HIDDEN_FAILS, 0).commit()
    }

    /** يتطلب أن تكون الخزنة مفتوحة. عملية ثقيلة: استدعِها خارج الخيط الرئيسي. */
    fun checkAccessSecret(password: CharArray): Boolean {
        val stored = prefs.getString(K_ACCESS, null) ?: return false
        val plain = try {
            AesGcm.decrypt(currentKey(), unb64(stored), AAD_ACCESS)
        } catch (e: java.security.GeneralSecurityException) {
            return false
        }
        val salt = plain.copyOfRange(0, 16)
        val expected = plain.copyOfRange(16, plain.size)
        val actual = PinKdf.derive(password, salt, ACCESS_ITERATIONS)
        val ok = java.security.MessageDigest.isEqual(expected, actual)
        plain.fill(0); expected.fill(0); actual.fill(0)
        if (ok) prefs.edit().putInt(K_HIDDEN_FAILS, 0).commit()
        return ok
    }

    /** يسجّل محاولة دخول فاشلة. يعيد true إذا بلغت الحد ويجب المسح. */
    fun registerAccessFailure(): Boolean {
        val fails = prefs.getInt(K_HIDDEN_FAILS, 0) + 1
        prefs.edit().putInt(K_HIDDEN_FAILS, fails).commit()
        return fails >= WIPE_THRESHOLD
    }

    // ---------------- البصمة ----------------

    /** الخطوة 1 للتفعيل: Cipher يُمرَّر إلى BiometricPrompt. */
    fun biometricEnrollCipher(): Cipher {
        return try {
            KeystoreCipher.biometricEncryptCipher(appContext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            KeystoreCipher.deleteBiometricKey()
            KeystoreCipher.biometricEncryptCipher(appContext)
        }
    }

    /** الخطوة 2 للتفعيل: بعد نجاح البصمة نغلّف مفتاح البيانات بمفتاح البصمة. */
    fun completeBiometricEnrollment(authenticatedCipher: Cipher) {
        val ct = authenticatedCipher.doFinal(currentKey())
        val blob = authenticatedCipher.iv + ct
        prefs.edit().putString(K_BIO_BLOB, b64(blob)).commit()
        _biometricEnabled.value = true
    }

    /**
     * Cipher لفك مفتاح البيانات بالبصمة، أو null إن لم تكن البصمة مفعّلة.
     * يرمي KeyPermanentlyInvalidatedException إذا أُضيفت بصمة جديدة للجهاز.
     */
    fun biometricUnlockCipher(): Cipher? {
        val blob = prefs.getString(K_BIO_BLOB, null)?.let { unb64(it) } ?: return null
        return KeystoreCipher.biometricDecryptCipher(blob.copyOfRange(0, 12))
    }

    fun completeBiometricUnlock(authenticatedCipher: Cipher) {
        val blob = unb64(prefs.getString(K_BIO_BLOB, null) ?: throw IllegalStateException("no biometric"))
        dataKey = authenticatedCipher.doFinal(blob, 12, blob.size - 12)
        resetFailures()
    }

    fun disableBiometric() {
        prefs.edit().remove(K_BIO_BLOB).commit()
        KeystoreCipher.deleteBiometricKey()
        _biometricEnabled.value = false
    }

    // ---------------- القفل والمسح ----------------

    /** يمحو مفتاح البيانات من الذاكرة. */
    fun lockMemory() {
        dataKey?.fill(0)
        dataKey = null
    }

    /**
     * مسح تشفيري: حذف مفاتيح Keystore وأغلفة مفتاح البيانات يجعل أي نسخة متبقية
     * من الملف المشفّر على وحدة التخزين غير قابلة للفك.
     */
    fun wipe() {
        lockMemory()
        prefs.edit().clear().commit()
        KeystoreCipher.deleteAll(appContext)
        _biometricEnabled.value = false
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val FREE_ATTEMPTS = 5
        const val WIPE_THRESHOLD = 10
        private const val BASE_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 60 * 60 * 1000L

        private val AAD_PIN = "dugy-pin-wrap-v1".toByteArray()
        private val AAD_ACCESS = "dugy-access-v1".toByteArray()
        private const val ACCESS_ITERATIONS = 120_000

        private const val K_SALT = "salt"
        private const val K_ITER = "iter"
        private const val K_WRAPPED = "wrapped_key"
        private const val K_BIO_BLOB = "bio_blob"
        private const val K_FAILS = "fails"
        private const val K_LOCK_UNTIL = "lock_until"
        private const val K_ACCESS = "access"
        private const val K_HIDDEN_FAILS = "access_fails"
    }
}
