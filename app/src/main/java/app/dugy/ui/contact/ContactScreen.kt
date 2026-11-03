@file:OptIn(ExperimentalMaterial3Api::class)

package app.dugy.ui.contact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.dugy.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * نموذج «تواصل معنا» عادي في ظاهره: الاسم والرسالة ثم «إرسال».
 *
 * إذا كان على الجهاز حساب مسجّل، وكُتب اسم المستخدم في خانة الاسم وكلمة مروره في خانة
 * الرسالة، تُفتح المحادثات. في أي حالة أخرى تظهر رسالة الشكر نفسها دائمًا.
 * المحاولات الخاطئة لاسم الحساب تُعدّ، وبعد عشر محاولات تُمسح البيانات.
 */
@Composable
fun ContactScreen(container: AppContainer, onBack: () -> Unit, onAccessGranted: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun showThanks() {
        busy = false
        name = ""
        body = ""
        sent = true
    }

    fun submit() {
        val enteredName = name.trim().removePrefix("@").lowercase()
        val secret = body.trim().toCharArray()
        busy = true
        sent = false
        scope.launch {
            val account = container.messenger.account.value
            val granted: Boolean? = if (account != null && enteredName == account.username && secret.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    try {
                        if (container.vault.hasAccessSecret()) {
                            container.vault.checkAccessSecret(secret)
                        } else {
                            // حساب من نسخة سابقة: نتحقق عبر الخادم مرة واحدة ثم نحفظ الأثر محليًا.
                            when (container.messenger.verifyPasswordOnline(account.username, String(secret))) {
                                true -> { container.vault.setAccessSecret(secret); true }
                                false -> false
                                null -> null
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            } else {
                // نفس الإيقاع تقريبًا في كل الحالات.
                delay(700)
                null
            }
            secret.fill('\u0000')

            when (granted) {
                true -> {
                    busy = false
                    name = ""
                    body = ""
                    onAccessGranted()
                }
                false -> {
                    val mustWipe = withContext(Dispatchers.Default) { container.vault.registerAccessFailure() }
                    if (mustWipe) {
                        container.lockManager.wipeEverything()
                        return@launch
                    }
                    showThanks()
                }
                null -> showThanks()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تواصل معنا") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "يسعدنا سماع رأيك واقتراحاتك، أو خاطرة تحب أن نضيفها.",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60); sent = false },
                label = { Text("الاسم") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it.take(1000); sent = false },
                label = { Text("رسالتك") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )
            Button(
                onClick = { submit() },
                enabled = !busy && name.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("إرسال")
            }
            if (sent) {
                Text(
                    "شكرًا لتواصلك، وصلتنا رسالتك.",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
