package app.dugy.ui.lock

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.dugy.AppContainer
import app.dugy.security.BiometricAuth
import app.dugy.security.UnlockResult
import app.dugy.ui.components.ConfirmDialog
import app.dugy.ui.components.findFragmentActivity
import app.dugy.ui.components.formatCountdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LockScreen(container: AppContainer) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val biometricEnabled by container.vault.biometricEnabled.collectAsStateWithLifecycle()

    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockoutMs by remember { mutableLongStateOf(container.vault.lockoutRemainingMs()) }
    var showForgot by remember { mutableStateOf(false) }
    var autoPrompted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            lockoutMs = container.vault.lockoutRemainingMs()
            delay(1000)
        }
    }

    fun startBiometric() {
        val act = activity ?: return
        val cipher = try {
            container.vault.biometricUnlockCipher() ?: return
        } catch (e: KeyPermanentlyInvalidatedException) {
            container.vault.disableBiometric()
            error = "تغيّرت البصمات المسجّلة في الجهاز، لذلك عُطّل الفتح بالبصمة. استخدم الرمز، ثم فعّل البصمة من جديد إن أردت."
            return
        } catch (e: Exception) {
            error = "تعذّر استخدام البصمة. استخدم الرمز."
            return
        }
        try {
            BiometricAuth.authenticate(
                activity = act,
                title = "فتح DUGY",
                subtitle = "استخدم بصمتك لفتح التطبيق",
                negativeText = "استخدام الرمز",
                cipher = cipher,
                onSuccess = { authed ->
                    scope.launch {
                        try {
                            container.vault.completeBiometricUnlock(authed)
                            container.lockManager.onUnlocked()
                        } catch (e: Exception) {
                            error = "تعذّر الفتح بالبصمة. استخدم الرمز."
                        }
                    }
                },
                onError = { msg -> if (msg != null) error = msg },
            )
        } catch (e: IllegalStateException) {
            error = "تعذّر عرض نافذة البصمة. استخدم الرمز."
        }
    }

    // عرض نافذة البصمة تلقائيًا مرة واحدة، فقط حين تكون الشاشة في المقدّمة فعلًا.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(biometricEnabled) {
        if (!biometricEnabled) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!autoPrompted) {
                autoPrompted = true
                startBiometric()
            }
        }
    }

    val lockedOut = lockoutMs > 0

    Scaffold { padding ->
        PinEntry(
            title = "DUGY مقفل",
            subtitle = if (lockedOut) "محاولات كثيرة غير صحيحة. حاول مجددًا بعد ${formatCountdown(lockoutMs)}" else null,
            error = error,
            busy = busy,
            enabled = !lockedOut,
            onSubmit = { pin ->
                busy = true
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        try {
                            container.vault.unlockWithPin(pin)
                        } finally {
                            pin.fill('\u0000')
                        }
                    }
                    when (result) {
                        UnlockResult.Success -> container.lockManager.onUnlocked()
                        is UnlockResult.WrongPin -> {
                            error = buildString {
                                append("رمز غير صحيح.")
                                result.freeAttemptsLeft?.let { append(" المحاولات المتبقية قبل فرض الانتظار: $it.") }
                            }
                            lockoutMs = result.lockoutMs
                        }
                        is UnlockResult.LockedOut -> lockoutMs = result.remainingMs
                        UnlockResult.MustWipe -> container.lockManager.wipeEverything()
                        UnlockResult.StorageError -> error =
                            "تعذّر فتح بيانات التطبيق على هذا الجهاز. قد تحتاج إلى مسح بيانات التطبيق والبدء من جديد."
                    }
                    busy = false
                }
            },
            modifier = Modifier.padding(padding),
        ) {
            if (biometricEnabled) {
                OutlinedButton(onClick = { startBiometric() }, enabled = !busy) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("الفتح بالبصمة")
                }
            }
            TextButton(onClick = { showForgot = true }) { Text("نسيت الرمز؟") }
        }
    }

    if (showForgot) {
        ConfirmDialog(
            title = "لا يمكن استعادة الرمز",
            body = "رمز القفل لا يُحفظ على أي خادم، ولا توجد طريقة لاستعادته.\n\n" +
                "الخيار الوحيد هو مسح كل بيانات التطبيق على هذا الجهاز والبدء من جديد. " +
                "لا يمكن التراجع عن ذلك.",
            confirmText = "مسح كل شيء والبدء من جديد",
            destructive = true,
            onConfirm = {
                showForgot = false
                container.lockManager.wipeEverything()
            },
            onDismiss = { showForgot = false },
        )
    }
}
