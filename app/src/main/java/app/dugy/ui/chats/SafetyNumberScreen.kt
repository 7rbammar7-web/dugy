@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.dugy.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dugy.AppContainer
import app.dugy.e2e.E2eEngine
import kotlinx.coroutines.launch

/**
 * رمز الأمان: يقارنه المستخدم مع جهة الاتصال وجهًا لوجه أو عبر قناة أخرى موثوقة.
 * تطابق الرمز على الهاتفين يعني أن لا أحد يقف بينهما.
 */
@Composable
fun SafetyNumberScreen(container: AppContainer, conversationId: String, onBack: () -> Unit) {
    val conversations by container.repository.conversations.collectAsStateWithLifecycle()
    val conv = conversations.firstOrNull { it.id == conversationId }
    if (conv == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val contact = conv.contact
    val securityVersion by container.messenger.securityVersion.collectAsStateWithLifecycle()
    val security = remember(securityVersion) { container.messenger.security(contact.handle) }
    var number by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(securityVersion, attempt) {
        failed = false
        number = runCatching { container.messenger.safetyNumber(contact.handle, contact.uid) }.getOrNull()
        failed = number == null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("رمز الأمان") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (icon, label, color) = when {
                security.keyChanged -> Triple(Icons.Outlined.GppMaybe, "تغيّر مفتاح الأمان — غير موثّقة", MaterialTheme.colorScheme.error)
                security.verified -> Triple(Icons.Outlined.VerifiedUser, "موثّقة: طابقتما رمز الأمان", MaterialTheme.colorScheme.secondary)
                else -> Triple(Icons.Outlined.VerifiedUser, "غير موثّقة بعد", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = color)
                Text(label, color = color, style = MaterialTheme.typography.titleMedium)
            }

            Text(
                "قارن الأرقام التالية مع ما يظهر على هاتف ${contact.name} (@${contact.handle}) وجهًا لوجه أو في مكالمة " +
                    "تعرف فيها صوته. إذا تطابقت كلها فرسائلكما لا يستطيع أحد غيركما قراءتها.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    val n = number
                    when {
                        n != null -> SafetyDigits(n)
                        failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "تعذّر جلب مفتاح جهة الاتصال. تحقق من الاتصال بالإنترنت.",
                                textAlign = TextAlign.Center,
                            )
                            OutlinedButton(onClick = { attempt++ }) { Text("إعادة المحاولة") }
                        }
                        else -> CircularProgressIndicator()
                    }
                }
            }

            if (number != null) {
                if (security.keyChanged) {
                    Button(
                        onClick = { scope.launch { container.messenger.acceptNewKey(conversationId, contact.handle) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("قبول المفتاح الجديد") }
                    Text(
                        "اقبل المفتاح الجديد فقط بعد أن تتأكد من صاحبه. سيعود الإرسال، وتبقى المحادثة غير موثّقة حتى تطابقا الرمز الجديد.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                } else if (security.verified) {
                    OutlinedButton(
                        onClick = { scope.launch { container.messenger.setVerified(contact.handle, false) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("إلغاء التوثيق") }
                } else {
                    Button(
                        onClick = { scope.launch { container.messenger.setVerified(contact.handle, true) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("طابقنا الرمز — وضع علامة «موثّقة»") }
                }
            }

            Text(
                "إذا تغيّر مفتاح جهة الاتصال لاحقًا ستزول علامة التوثيق، ويتوقف الإرسال حتى تقرر بنفسك.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** الأرقام تُعرض دائمًا من اليسار إلى اليمين بخط ثابت العرض لتسهيل المقارنة. */
@Composable
private fun SafetyDigits(number: String) {
    val groups = E2eEngine.formatSafetyNumber(number)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { g ->
                        Text(
                            g,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
                        )
                    }
                }
            }
        }
    }
}
