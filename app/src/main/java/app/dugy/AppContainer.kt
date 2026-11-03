package app.dugy

import android.content.Context
import app.dugy.data.ChatRepository
import app.dugy.data.MessageTransport
import app.dugy.data.PrototypeTransport
import app.dugy.data.SettingsStore
import app.dugy.security.LockManager
import app.dugy.security.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** حاوية تبعيات بسيطة بلا مكتبات حقن. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsStore(appContext)
    val vault = Vault(appContext)
    val repository = ChatRepository(appContext, appScope)

    /** يُستبدل في المرحلة الثانية بتنفيذ يعتمد بروتوكول تشفير طرفي معتمد. */
    val transport: MessageTransport = PrototypeTransport()

    val lockManager = LockManager(settings, vault, repository)
}
