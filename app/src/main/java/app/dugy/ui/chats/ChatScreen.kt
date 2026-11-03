@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.dugy.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dugy.AppContainer
import app.dugy.data.Message
import app.dugy.data.MessageStatus
import app.dugy.data.Retention
import app.dugy.ui.components.ConfirmDialog
import app.dugy.ui.components.PrototypeBanner
import app.dugy.ui.components.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MAX_MESSAGE_CHARS = 4000

@Composable
fun ChatScreen(container: AppContainer, conversationId: String, onBack: () -> Unit) {
    val conversations by container.repository.conversations.collectAsStateWithLifecycle()
    val conv = conversations.firstOrNull { it.id == conversationId }

    if (conv == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // remember وليس rememberSaveable: لا نريد نص المسودة داخل حالة النشاط المحفوظة.
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showRetention by remember { mutableStateOf(false) }
    var showDeleteConv by remember { mutableStateOf(false) }
    var showVerify by remember { mutableStateOf(false) }
    var messageToDelete by remember { mutableStateOf<Message?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(conversationId) {
        while (true) {
            container.repository.purgeExpired()
            delay(30_000)
        }
    }
    LaunchedEffect(conv.messages.size) {
        if (conv.messages.isNotEmpty()) listState.animateScrollToItem(conv.messages.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        val contact = conv.contact
        container.appScope.launch {
            val msg = container.repository.addOutgoing(conversationId, text) ?: return@launch
            val status = try {
                container.transport.send(contact, msg.id, text)
            } catch (e: Exception) {
                MessageStatus.FAILED
            }
            container.repository.updateStatus(conversationId, msg.id, status)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                title = {
                    Column {
                        Text(conv.contact.name, maxLines = 1)
                        Text(
                            if (conv.retentionMs != null) "رسائل مؤقتة: ${Retention.label(conv.retentionMs)}"
                            else "جهة اتصال غير موثّقة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showVerify = true }) {
                        Icon(Icons.Outlined.VerifiedUser, contentDescription = "التحقق من الهوية")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "خيارات")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("الرسائل المؤقتة") },
                            leadingIcon = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                            onClick = { menuOpen = false; showRetention = true },
                        )
                        DropdownMenuItem(
                            text = { Text("حذف المحادثة من هذا الجهاز", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; showDeleteConv = true },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { if (it.length <= MAX_MESSAGE_CHARS) input = it },
                        placeholder = { Text("اكتب رسالتك…") },
                        maxLines = 5,
                        // اتجاه النص يتبع محتواه، فتظهر الجمل الإنجليزية بمحاذاة صحيحة داخل واجهة عربية.
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                        supportingText = if (input.length > MAX_MESSAGE_CHARS - 200) {
                            { Text("${input.length} / $MAX_MESSAGE_CHARS") }
                        } else null,
                        modifier = Modifier.weight(1f),
                    )
                    FilledIconButton(
                        onClick = { send() },
                        enabled = input.isNotBlank(),
                        modifier = Modifier.padding(bottom = 4.dp).size(52.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!container.transport.isEndToEndEncrypted) PrototypeBanner()
            if (conv.messages.isEmpty()) {
                Text(
                    "اكتب أول رسالة بالأسفل. لا توجد قوالب جاهزة؛ اكتب ما تشاء.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(conv.messages, key = { it.id }) { msg ->
                    MessageBubble(msg, onLongPress = { messageToDelete = msg })
                }
            }
        }
    }

    messageToDelete?.let { msg ->
        ConfirmDialog(
            title = "حذف الرسالة من هذا الجهاز؟",
            body = if (msg.status == MessageStatus.LOCAL_ONLY) {
                "تُحذف من هذا الجهاز فقط. لم تُرسل هذه الرسالة إلى أحد، فلا توجد منها نسخة على خادم أو عند طرف آخر."
            } else {
                "تُحذف من هذا الجهاز فقط. إن كانت قد وصلت إلى الطرف الآخر فقد يحتفظ بنسخة أو يصوّرها."
            },
            confirmText = "حذف",
            destructive = true,
            onConfirm = {
                container.repository.deleteMessage(conversationId, msg.id)
                messageToDelete = null
            },
            onDismiss = { messageToDelete = null },
        )
    }

    if (showDeleteConv) {
        ConfirmDialog(
            title = "حذف المحادثة؟",
            body = "تُحذف المحادثة وكل رسائلها من هذا الجهاز فقط. لا يمكن التراجع.",
            confirmText = "حذف المحادثة",
            destructive = true,
            onConfirm = {
                showDeleteConv = false
                container.repository.deleteConversation(conversationId)
                onBack()
            },
            onDismiss = { showDeleteConv = false },
        )
    }

    if (showRetention) {
        RetentionDialog(
            current = conv.retentionMs,
            onSelect = {
                container.repository.setRetention(conversationId, it)
                showRetention = false
            },
            onDismiss = { showRetention = false },
        )
    }

    if (showVerify) {
        AlertDialog(
            onDismissRequest = { showVerify = false },
            icon = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) },
            title = { Text("التحقق من هوية جهة الاتصال") },
            text = {
                Text(
                    "في النسخة الكاملة ستقارن مع جهة الاتصال «رمز أمان» رقميًا أو تمسح رمز QR من جهازها، " +
                        "وسيظهر تحذير واضح إذا تغيّر مفتاحها.\n\n" +
                        "هذه الميزة غير متاحة في النموذج الأولي لأن التشفير الطرفي لم يُدمج بعد، " +
                        "لذلك لا تُعدّ هذه المحادثة موثّقة."
                )
            },
            confirmButton = { TextButton(onClick = { showVerify = false }) { Text("حسنًا") } },
        )
    }
}

@Composable
private fun MessageBubble(msg: Message, onLongPress: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        // المرسَلة في جهة النهاية (اليسار في RTL)، كما تنعكس الواجهات في أندرويد.
        horizontalArrangement = if (msg.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (msg.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = "حذف الرسالة"),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                )
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        formatTime(msg.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (msg.outgoing) {
                        val (icon, label) = statusInfo(msg.status)
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (msg.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun statusInfo(status: MessageStatus): Pair<ImageVector, String> = when (status) {
    MessageStatus.PENDING -> Icons.Outlined.Schedule to "قيد الإرسال"
    MessageStatus.SENT -> Icons.Outlined.Done to "أُرسلت"
    MessageStatus.DELIVERED -> Icons.Outlined.DoneAll to "تم التسليم"
    MessageStatus.FAILED -> Icons.Outlined.ErrorOutline to "فشل الإرسال"
    MessageStatus.LOCAL_ONLY -> Icons.Outlined.PhonelinkLock to "محفوظة محليًا — لم تُرسل"
}

@Composable
private fun RetentionDialog(current: Long?, onSelect: (Long?) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الرسائل المؤقتة") },
        text = {
            Column {
                Retention.options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == value, onClick = { selected = value }, role = Role.RadioButton)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == value, onClick = null)
                        Spacer(Modifier.size(8.dp))
                        Text(label)
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    "تُحذف الرسائل الأقدم من المدة المختارة من هذا الجهاز. في النسخة الكاملة يتفق عليها الطرفان " +
                        "وتُحذف من الخادم تلقائيًا. الحذف لا يضمن محو نسخ حفظها الطرف الآخر أو صوّرها.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(selected) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
