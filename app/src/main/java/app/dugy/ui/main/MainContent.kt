@file:OptIn(ExperimentalMaterial3Api::class)

package app.dugy.ui.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dugy.AppContainer
import app.dugy.notify.Notifier
import app.dugy.ui.account.AccountScreen
import app.dugy.ui.chats.ChatScreen
import app.dugy.ui.chats.ConversationsTab
import app.dugy.ui.chats.NewChatDialog
import app.dugy.ui.chats.SafetyNumberScreen
import app.dugy.ui.contact.ContactScreen
import app.dugy.ui.quotes.QuotesTab
import app.dugy.ui.settings.ChangePinScreen
import app.dugy.ui.settings.SettingsTab

private sealed interface Route {
    data object Tabs : Route
    data object Contact : Route
    data object Account : Route
    data object Inbox : Route
    data class Chat(val id: String) : Route
    data class SafetyNumber(val conversationId: String) : Route
    data object ChangePin : Route
}

private data class TabItem(val label: String, val title: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("الخواطر", "الخواطر", Icons.Outlined.AutoStories),
    TabItem("الخصوصية", "الخصوصية والإعدادات", Icons.Outlined.Shield),
)

/**
 * المحتوى بعد فتح القفل. التنقل بمكدس بسيط في الذاكرة؛ عند القفل يُزال هذا المكوّن
 * بالكامل فلا تبقى أي شاشة معروضة.
 */
@Composable
fun MainContent(container: AppContainer) {
    val context = LocalContext.current
    var stack by remember { mutableStateOf(listOf<Route>(Route.Tabs)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showNewChat by remember { mutableStateOf(false) }
    val account by container.messenger.account.collectAsStateWithLifecycle()

    fun push(route: Route) {
        stack = stack + route
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    // إذن التنبيهات يُطلب مرة واحدة فقط (أندرويد 13 فما فوق).
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        container.settings.setNotificationsAsked()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Notifier.hasPermission(context) && !container.settings.current.notificationsAsked
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // لا تنبيهات ما دامت المحادثات معروضة.
    val top = stack.last()
    val hidden = top is Route.Inbox || top is Route.Chat || top is Route.SafetyNumber
    SideEffect { container.messenger.hiddenOpen = hidden }
    DisposableEffect(Unit) { onDispose { container.messenger.hiddenOpen = false } }

    // إن خرج الحساب أثناء عرض المحادثات نعود إلى البداية.
    LaunchedEffect(account, hidden) {
        if (hidden && account == null) stack = listOf(Route.Tabs)
    }

    when (top) {
        Route.Tabs -> Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(tabs[tab].title) },
                    actions = {
                        IconButton(onClick = { container.lockManager.lock() }) {
                            Icon(Icons.Outlined.Lock, contentDescription = "قفل الآن")
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, item ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            val mod = Modifier.padding(padding)
            when (tab) {
                0 -> QuotesTab(container, mod)
                else -> SettingsTab(
                    container,
                    mod,
                    onChangePin = { push(Route.ChangePin) },
                    onOpenAccount = { push(Route.Account) },
                    onOpenContact = { push(Route.Contact) },
                )
            }
        }

        Route.Contact -> ContactScreen(
            container,
            onBack = { pop() },
            // تُستبدل شاشة «تواصل معنا» فلا يعود زر الرجوع إليها.
            onAccessGranted = { stack = listOf(Route.Tabs, Route.Inbox) },
        )

        Route.Account -> BackScaffold(title = "الحساب", onBack = { pop() }) { mod ->
            AccountScreen(container, mod, onDone = { pop() })
        }

        Route.Inbox -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("المحادثات") },
                    navigationIcon = {
                        IconButton(onClick = { pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                        }
                    },
                )
            },
            floatingActionButton = {
                if (account != null) {
                    ExtendedFloatingActionButton(
                        onClick = { showNewChat = true },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("محادثة جديدة") },
                    )
                }
            },
        ) { padding ->
            ConversationsTab(container, Modifier.padding(padding), onOpenChat = { push(Route.Chat(it)) })
        }

        is Route.Chat -> ChatScreen(
            container,
            top.id,
            onBack = { pop() },
            onOpenSafetyNumber = { push(Route.SafetyNumber(top.id)) },
        )
        is Route.SafetyNumber -> SafetyNumberScreen(container, top.conversationId, onBack = { pop() })
        Route.ChangePin -> ChangePinScreen(container, onDone = { pop() })
    }

    if (showNewChat && top is Route.Inbox) {
        NewChatDialog(
            container,
            onDismiss = { showNewChat = false },
            onOpened = { id ->
                showNewChat = false
                push(Route.Chat(id))
            },
        )
    }
}

@Composable
private fun BackScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding -> content(Modifier.padding(padding)) }
}
