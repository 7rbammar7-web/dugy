package app.dugy.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.dugy.data.ChatRepository
import app.dugy.data.SettingsStore
import app.dugy.net.Messenger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppPhase { ONBOARDING, PIN_SETUP, LOCKED, UNLOCKED }

/**
 * يتحكم في حالة القفل. يُسجَّل مراقبًا لدورة حياة العملية (ProcessLifecycleOwner)،
 * فيُقفل التطبيق فور مغادرته دائمًا (ليس خيارًا).
 */
class LockManager(
    private val settings: SettingsStore,
    private val vault: Vault,
    private val repository: ChatRepository,
    private val messenger: Messenger,
) : DefaultLifecycleObserver {

    private val _phase = MutableStateFlow(computePhase())
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    init {
        // وهو مقفل: نراقب وصول الجديد فقط لإظهار تنبيه «خاطرة جديدة».
        if (_phase.value == AppPhase.LOCKED) messenger.startWatcher()
    }

    private fun computePhase(): AppPhase = when {
        !settings.current.onboardingDone -> AppPhase.ONBOARDING
        !vault.isSetUp -> AppPhase.PIN_SETUP
        else -> AppPhase.LOCKED
    }

    fun finishOnboarding() {
        settings.setOnboardingDone(true)
        _phase.value = computePhase()
    }

    /** يُستدعى بعد نجاح فتح الخزنة (رمز أو بصمة أو إعداد أول). */
    suspend fun onUnlocked() {
        repository.open(vault.currentKey())
        _phase.value = AppPhase.UNLOCKED
        messenger.open(vault.currentKey())
    }

    fun lock() {
        if (_phase.value == AppPhase.UNLOCKED) {
            messenger.close()
            repository.close()
            vault.lockMemory()
            _phase.value = AppPhase.LOCKED
            messenger.startWatcher()
        }
    }

    /** مسح كل البيانات المحلية والمفاتيح، والعودة إلى البداية. */
    fun wipeEverything() {
        messenger.wipe()
        repository.close()
        repository.deleteFiles()
        vault.wipe()
        settings.clearAll()
        _phase.value = AppPhase.ONBOARDING
    }

    override fun onStop(owner: LifecycleOwner) {
        lock()
    }
}
