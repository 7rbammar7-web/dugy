package app.dugy.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.dugy.AppContainer
import app.dugy.ui.lock.PIN_MAX
import app.dugy.ui.lock.PIN_MIN
import app.dugy.ui.lock.PinEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** تغيير الرمز: الحالي ← الجديد ← التأكيد. مفتاح البيانات لا يتغير؛ يُعاد تغليفه فقط. */
@Composable
fun ChangePinScreen(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<CharArray?>(null) }
    var newPin by remember { mutableStateOf<CharArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun clearAll() {
        current?.fill('\u0000')
        newPin?.fill('\u0000')
        current = null
        newPin = null
    }

    DisposableEffect(Unit) { onDispose { clearAll() } }
    BackHandler(enabled = !busy) { clearAll(); onDone() }

    Scaffold { padding ->
        val mod = Modifier.padding(padding)
        val cancel: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            TextButton(enabled = !busy, onClick = { clearAll(); onDone() }) { Text("إلغاء") }
        }
        when (step) {
            0 -> PinEntry(
                title = "أدخل الرمز الحالي",
                subtitle = null,
                error = error,
                busy = false,
                enabled = true,
                onSubmit = { pin ->
                    current = pin
                    error = null
                    step = 1
                },
                modifier = mod,
                extra = cancel,
            )
            1 -> PinEntry(
                title = "أدخل الرمز الجديد",
                subtitle = "من $PIN_MIN إلى $PIN_MAX أرقام.",
                error = error,
                busy = false,
                enabled = true,
                onSubmit = { pin ->
                    newPin = pin
                    error = null
                    step = 2
                },
                modifier = mod,
                extra = cancel,
            )
            else -> PinEntry(
                title = "أكّد الرمز الجديد",
                subtitle = null,
                error = error,
                busy = busy,
                enabled = true,
                onSubmit = { confirm ->
                    val cur = current
                    val np = newPin
                    if (cur == null || np == null || !confirm.contentEquals(np)) {
                        confirm.fill('\u0000')
                        newPin?.fill('\u0000')
                        newPin = null
                        error = "الرمزان الجديدان غير متطابقين."
                        step = 1
                        return@PinEntry
                    }
                    confirm.fill('\u0000')
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { container.vault.changePin(cur, np) }
                        busy = false
                        clearAll()
                        if (ok) {
                            Toast.makeText(context, "تم تغيير رمز القفل", Toast.LENGTH_SHORT).show()
                            onDone()
                        } else {
                            error = "الرمز الحالي غير صحيح."
                            step = 0
                        }
                    }
                },
                modifier = mod,
                extra = cancel,
            )
        }
    }
}
