package app.dugy

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import app.dugy.notify.Notifier

class DugyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannel(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.lockManager)
    }
}
