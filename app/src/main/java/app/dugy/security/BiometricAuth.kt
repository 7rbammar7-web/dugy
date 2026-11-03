package app.dugy.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

object BiometricAuth {
    private const val STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * يعرض نافذة البصمة مرتبطة بـ Cipher (CryptoObject)، فلا يصبح المفتاح قابلًا للاستخدام
     * إلا بعد مصادقة حيوية قوية فعلية، وليس مجرد ردّ «نجح» من الواجهة.
     *
     * @param onError يُستدعى بـ null عند الإلغاء من المستخدم، أو برسالة النظام عند الخطأ.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        negativeText: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onError: (String?) -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authed = result.cryptoObject?.cipher
                if (authed != null) onSuccess(authed) else onError("تعذّر التحقق من البصمة.")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onError(if (cancelled) null else errString.toString())
            }
        }

        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(STRONG)
            .setConfirmationRequired(false)
        if (subtitle != null) builder.setSubtitle(subtitle)

        prompt.authenticate(builder.build(), BiometricPrompt.CryptoObject(cipher))
    }
}
