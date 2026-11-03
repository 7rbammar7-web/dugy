package app.dugy.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dugy.AppContainer
import app.dugy.ui.components.EmptyState
import app.dugy.ui.components.PrototypeBanner
import app.dugy.ui.components.formatShort

@Composable
fun ConversationsTab(container: AppContainer, modifier: Modifier = Modifier, onOpenChat: (String) -> Unit) {
    val conversations by container.repository.conversations.collectAsStateWithLifecycle()
    val loadError by container.repository.loadError.collectAsStateWithLifecycle()
    val sorted = conversations.sortedByDescending { it.lastActivity }

    Column(modifier.fillMaxSize()) {
        if (!container.transport.isEndToEndEncrypted) PrototypeBanner()

        if (loadError) {
            ListItem(
                leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                headlineContent = { Text("تعذّر فك ملف المحادثات المحفوظ") },
                supportingContent = {
                    Text("لن يكتب التطبيق فوق الملف الحالي حمايةً لبياناتك. يمكنك مسح البيانات المحلية من إعدادات الخصوصية.")
                },
            )
        }

        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Forum,
                    title = "لا توجد محادثات بعد",
                    body = "اضغط «محادثة جديدة» وأضف اسم جهة الاتصال لتبدأ. " +
                        "في هذا النموذج الأولي تُحفظ رسائلك مشفّرة على جهازك ولا تُرسل إلى أحد.",
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(sorted, key = { it.id }) { conv ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenChat(conv.id) },
                        leadingContent = { Avatar(conv.contact.name) },
                        headlineContent = { Text(conv.contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                conv.messages.lastOrNull()?.text ?: "لا رسائل بعد",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Text(formatShort(conv.lastActivity), style = MaterialTheme.typography.labelSmall)
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun Avatar(name: String) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).ifEmpty { "؟" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun NewChatDialog(onDismiss: () -> Unit, onCreate: (name: String, handle: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("محادثة جديدة") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 60) name = it },
                    label = { Text("اسم جهة الاتصال") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = handle,
                    onValueChange = { if (it.length <= 80) handle = it },
                    label = { Text("المعرّف (اختياري)") },
                    supportingText = {
                        Text("في النسخة الكاملة سيكون معرّف حساب أو رمز دعوة، ويُتحقق منه برمز الأمان.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, handle) }) { Text("إنشاء") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
