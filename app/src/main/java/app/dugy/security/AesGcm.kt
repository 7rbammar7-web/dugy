package app.dugy.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * غلاف رفيع حول AES-256-GCM القياسي من JCA لتشفير البيانات المحفوظة على الجهاز.
 *
 * ليس بروتوكولًا مخصصًا: هو تشفير موثّق (AEAD) معياري بمتجه تهيئة عشوائي لكل عملية.
 * التشفير الطرفي بين الأجهزة لا يُبنى على هذا الملف، بل على مكتبة بروتوكول معتمدة
 * في المرحلة الثانية (انظر docs/PHASE2_E2E_PLAN.md).
 */
object AesGcm {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encrypt(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == 32) { "AES-256 key required" }
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    /** يرمي AEADBadTagException إذا كان المفتاح خاطئًا أو البيانات معدّلة. */
    fun decrypt(key: ByteArray, blob: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == 32) { "AES-256 key required" }
        require(blob.size > IV_BYTES) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES)
        )
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }
}
