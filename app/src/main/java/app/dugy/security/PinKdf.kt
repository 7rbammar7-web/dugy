package app.dugy.security

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * اشتقاق مفتاح من رمز القفل باستخدام PBKDF2-HMAC-SHA256 (متوفر في أندرويد 8 فما فوق).
 * لا يُخزّن الرمز نفسه في أي مكان. الناتج يُستخدم لتغليف مفتاح البيانات فقط.
 *
 * العدد المرتفع للتكرارات يبطئ التخمين، والتغليف الإضافي عبر Android Keystore
 * يمنع تجربة الرموز خارج الجهاز حتى لو نُسخت الملفات.
 */
object PinKdf {
    const val ITERATIONS = 310_000
    private const val KEY_BITS = 256

    fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }
}
