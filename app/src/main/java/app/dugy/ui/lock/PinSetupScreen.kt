package app.dugy.ui.lock

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.dugy.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PinSetupScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<CharArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold { padding ->
        val pending = first
        if (pending == null) {
            PinEntry(
                title = "أنشئ رمز القفل",
                subtitle = "من $PIN_MIN إلى $PIN_MAX أرقام. يحمي الرمز بياناتك على هذا الجهاز، " +
                    "ولا يُرسل إلى أي خادم. إذا نسيته فلن يمكن استعادة بياناتك.",
                error = error,
                busy = false,
                enabled = true,
                onSubmit = { pin ->
                    error = null
                    first = pin
                },
                modifier = Modifier.padding(padding),
            )
        } else {
            PinEntry(
                title = "أعد إدخال الرمز للتأكيد",
                subtitle = null,
                error = error,
                busy = busy,
                enabled = true,
                onSubmit = { confirm ->
                    if (!confirm.contentEquals(pending)) {
                        confirm.fill('\u0000')
                        pending.fill('\u0000')
                        first = null
                        error = "الرمزان غير متطابقين. ابدأ من جديد."
                        return@PinEntry
                    }
                    confirm.fill('\u0000')
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.Default) {
                            try {
                                container.vault.setup(pending)
                            } finally {
                                pending.fill('\u0000')
                            }
                        }
                        container.lockManager.onUnlocked()
                    }
                },
                modifier = Modifier.padding(padding),
            ) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pending.fill('\u0000')
                        first = null
                        error = null
                    },
                ) { Text("رجوع") }
            }
        }
    }
}
