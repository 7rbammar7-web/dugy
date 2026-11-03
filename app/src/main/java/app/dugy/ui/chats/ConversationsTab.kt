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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dugy.AppContainer
import app.dugy.data.MessageStatus
import app.dugy.ui.components.EmptyState
import app.dugy.ui.components.NetworkBanner
import app.dugy.ui.components.formatShort
import kotlinx.coroutines.launch

@Composable
fun ConversationsTab(container: AppContainer, modifier: Modifier = Modifier, onOpenChat: (String) -> Unit) {
    val messenger = container.messenger

    if (!messenger.isConfigured) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = "غير متاح",
                body = "هذه الميزة غير متاحة في هذه النسخة.",
            )
        }
        return
    }

    val account by messenger.account.collectAsStateWithLifecycle()
    if (account == null) return

    val conversations by container.repository.conversations.collectAsStateWithLifecycle()
    val loadError by container.repository.loadError.collectAsStateWithLifecycle()
    // يُقرأ ليعاد رسم شارات التوثيق عند تغيّرها.
    val securityVersion by messenger.securityVersion.collectAsStateWithLifecycle()
    val sorted = conversations.sortedByDescending { it.lastActivity }

    Column(modifier.fillMaxSize()) {
        NetworkBanner(container)

        if (loadError) {
            ListItem(
                leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                headlineContent = { Text("تعذّر فك ملف المحادثات المحفوظ") },
                supportingContent = {
                    Text("لن يكتب التطبيق فوق الملف الحالي حمايةً لبياناتك.")
                },
            )
        }

        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Forum,
                    title = "لا توجد محادثات بعد",
                    body = "اضغط «محادثة جديدة» واكتب اسم مستخدم صديقك.",
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(sorted, key = { it.id }) { conv ->
                    val security = remember(securityVersion, conv.contact.handle) {
                        messenger.security(conv.contact.handle)
                    }
                    val last = conv.messages.lastOrNull()
                    ListItem(
                        modifier = Modifier.clickable { onOpenChat(conv.id) },
                        leadingContent = { Avatar(conv.contact.name) },
                        headlineContent = { Text(conv.contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                when {
                                    security.keyChanged -> "تغيّر رمز التحقق لهذه الجهة"
                                    last == null -> "لا رسائل بعد"
                                    last.outgoing && last.status == MessageStatus.FAILED -> "لم تُرسل: ${last.text}"
                                    else -> last.text
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (security.keyChanged) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatShort(conv.lastActivity), style = MaterialTheme.typography.labelSmall)
                                when {
                                    security.keyChanged -> Icon(
                                        Icons.Outlined.GppMaybe,
                                        contentDescription = "تغيّر رمز التحقق",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    security.verified -> Icon(
                                        Icons.Outlined.VerifiedUser,
                                        contentDescription = "موثّقة",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
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
            name.trim().take(1).uppercase().ifEmpty { "؟" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** بدء محادثة باسم مستخدم موجود فعلًا على الخادم. */
@Composable
fun NewChatDialog(container: AppContainer, onDismiss: () -> Unit, onOpened: (String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("محادثة جديدة") },
        text = {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it.trim().removePrefix("@").lowercase().take(20)
                        error = null
                    },
                    label = { Text("اسم المستخدم") },
                    prefix = { Text("@") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    supportingText = {
                        Text(
                            error ?: "اطلب من صديقك اسم مستخدمه. بعد بدء المحادثة طابقا «رمز التحقق» للتأكد من هويته.",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.length >= 3 && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val result = container.messenger.startConversation(username)
                        busy = false
                        result.onSuccess(onOpened).onFailure { error = it.message ?: "تعذّر بدء المحادثة." }
                    }
                },
            ) { Text("بدء") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء") } },
    )
}
