package app.dugy.ui.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dugy.AppContainer
import app.dugy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * إنشاء حساب أو تسجيل الدخول باسم مستخدم وكلمة مرور فقط (من الإعدادات).
 * لا يُطلب رقم هاتف ولا بريد إلكتروني.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(container: AppContainer, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    var creating by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (creating && password != confirm) {
            error = "كلمتا المرور غير متطابقتين."
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = if (creating) {
                container.messenger.createAccount(username, password, invite)
            } else {
                container.messenger.signIn(username, password)
            }
            if (result == null) {
                // أثر محلي لكلمة المرور للدخول لاحقًا من «تواصل معنا» دون إنترنت.
                val secret = password.toCharArray()
                withContext(Dispatchers.Default) {
                    runCatching { container.vault.setAccessSecret(secret) }
                }
                secret.fill('\u0000')
                password = ""
                confirm = ""
                busy = false
                onDone()
            } else {
                busy = false
                error = result
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(painterResource(R.drawable.dugy_logo), contentDescription = null, modifier = Modifier.size(72.dp))
        Text(
            if (creating) "إنشاء حساب" else "تسجيل الدخول",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "يكفي اسم مستخدم وكلمة مرور. لا نطلب رقم هاتف ولا بريدًا إلكترونيًا.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = creating,
                onClick = { creating = true; error = null },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("حساب جديد") }
            SegmentedButton(
                selected = !creating,
                onClick = { creating = false; error = null },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("لدي حساب") }
        }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it.trim().removePrefix("@").lowercase().take(20); error = null },
            label = { Text("اسم المستخدم") },
            prefix = { Text("@") },
            supportingText = { Text("حروف إنجليزية صغيرة وأرقام و _ ، من 3 إلى 20.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(128); error = null },
            label = { Text("كلمة المرور") },
            supportingText = if (creating) {
                { Text("8 أحرف على الأقل. لا يمكن استرجاعها إن نسيتها.") }
            } else null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (creating) {
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it.take(128); error = null },
                label = { Text("تأكيد كلمة المرور") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = invite,
                onValueChange = { invite = it.take(20).uppercase(); error = null },
                label = { Text("رمز الدعوة") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { submit() },
            enabled = !busy && username.length >= 3 && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text(if (creating) "إنشاء الحساب" else "دخول")
            }
        }
    }
}
