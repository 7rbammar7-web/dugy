package app.dugy.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyMissingException : Exception("Keystore key missing")

/**
 * مفاتيح AES غير قابلة للتصدير داخل Android Keystore.
 * يُجرَّب StrongBox أولًا إن كان الجهاز يدعمه، ثم يُستخدم المخزن الآمن العادي (TEE) بديلًا.
 */
object KeystoreCipher {
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private const val MASTER_ALIAS = "dugy.master.v1"
    private const val BIO_ALIAS = "dugy.bio.v1"

    private const val META_PREFS = "ks_meta"
    private const val META_STRONGBOX = "master_strongbox"

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existing(alias: String): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    fun deviceHasStrongBox(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /** هل وُلّد المفتاح الرئيسي داخل StrongBox؟ (null إن لم يُنشأ بعد) */
    fun masterIsStrongBox(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(META_STRONGBOX)) prefs.getBoolean(META_STRONGBOX, false) else null
    }

    // ---------- المفتاح الرئيسي (بلا مصادقة مستخدم؛ يحمي غلاف رمز القفل) ----------

    fun encrypt(context: Context, plain: ByteArray): ByteArray {
        val key = existing(MASTER_ALIAS) ?: generateMaster(context)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val key = existing(MASTER_ALIAS) ?: throw KeyMissingException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun generateMaster(context: Context): SecretKey {
        val (key, strongBox) = generate(context, MASTER_ALIAS, requireBiometric = false)
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(META_STRONGBOX, strongBox).apply()
        return key
    }

    // ---------- مفتاح البصمة (يتطلب مصادقة حيوية قوية لكل استخدام) ----------

    /** Cipher جاهز للتشفير؛ يُمرَّر إلى BiometricPrompt ولا يعمل إلا بعد نجاح البصمة. */
    fun biometricEncryptCipher(context: Context): Cipher {
        val key = existing(BIO_ALIAS) ?: generate(context, BIO_ALIAS, requireBiometric = true).first
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /** قد يرمي KeyPermanentlyInvalidatedException إذا تغيّرت البصمات المسجلة. */
    fun biometricDecryptCipher(iv: ByteArray): Cipher {
        val key = existing(BIO_ALIAS) ?: throw KeyMissingException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher
    }

    fun deleteBiometricKey() = delete(BIO_ALIAS)

    fun deleteAll(context: Context) {
        delete(MASTER_ALIAS)
        delete(BIO_ALIAS)
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun delete(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    // ---------- التوليد ----------

    private fun generate(context: Context, alias: String, requireBiometric: Boolean): Pair<SecretKey, Boolean> {
        if (deviceHasStrongBox(context)) {
            try {
                return generateInternal(alias, requireBiometric, strongBox = true) to true
            } catch (e: Exception) {
                // StrongBoxUnavailableException أو عدم دعم الخيارات: ننتقل إلى المخزن العادي.
                delete(alias)
            }
        }
        return generateInternal(alias, requireBiometric, strongBox = false) to false
    }

    private fun generateInternal(alias: String, requireBiometric: Boolean, strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        if (requireBiometric) {
            builder.setUserAuthenticationRequired(true)
            builder.setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                requireAuthEveryUseLegacy(builder)
            }
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        return generator.generateKey()
    }

    @Suppress("DEPRECATION")
    private fun requireAuthEveryUseLegacy(builder: KeyGenParameterSpec.Builder) {
        builder.setUserAuthenticationValidityDurationSeconds(-1)
    }
}
