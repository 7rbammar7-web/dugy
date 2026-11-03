@file:OptIn(ExperimentalMaterial3Api::class)

package app.dugy.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Forum
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.dugy.AppContainer
import app.dugy.ui.chats.ChatScreen
import app.dugy.ui.chats.ConversationsTab
import app.dugy.ui.chats.NewChatDialog
import app.dugy.ui.chats.SafetyNumberScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dugy.ui.quotes.QuotesTab
import app.dugy.ui.settings.ChangePinScreen
import app.dugy.ui.settings.SettingsTab
import app.dugy.ui.settings.ThreatModelScreen

private sealed interface Route {
    data object Tabs : Route
    data class Chat(val id: String) : Route
    data class SafetyNumber(val conversationId: String) : Route
    data object ThreatModel : Route
    data object ChangePin : Route
}

private data class TabItem(val label: String, val title: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("الخواطر", "الخواطر", Icons.Outlined.AutoStories),
    TabItem("الرسائل", "المحادثات الخاصة", Icons.Outlined.Forum),
    TabItem("الخصوصية", "الخصوصية والأمان", Icons.Outlined.Shield),
)

/**
 * المحتوى بعد فتح القفل. التنقل بمكدس بسيط في الذاكرة؛ عند القفل يُزال هذا المكوّن
 * بالكامل فلا تبقى أي شاشة محادثة معروضة.
 */
@Composable
fun MainContent(container: AppContainer) {
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

    when (val top = stack.last()) {
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
            floatingActionButton = {
                if (tab == 1 && account != null) {
                    ExtendedFloatingActionButton(
                        onClick = { showNewChat = true },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("محادثة جديدة") },
                    )
                }
            },
        ) { padding ->
            val mod = Modifier.padding(padding)
            when (tab) {
                0 -> QuotesTab(container, mod)
                1 -> ConversationsTab(container, mod, onOpenChat = { push(Route.Chat(it)) })
                else -> SettingsTab(
                    container,
                    mod,
                    onOpenThreatModel = { push(Route.ThreatModel) },
                    onChangePin = { push(Route.ChangePin) },
                )
            }
        }

        is Route.Chat -> ChatScreen(
            container,
            top.id,
            onBack = { pop() },
            onOpenSafetyNumber = { push(Route.SafetyNumber(top.id)) },
        )
        is Route.SafetyNumber -> SafetyNumberScreen(container, top.conversationId, onBack = { pop() })
        Route.ThreatModel -> ThreatModelScreen(onBack = { pop() })
        Route.ChangePin -> ChangePinScreen(container, onDone = { pop() })
    }

    if (showNewChat) {
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
