package app.dugy.security

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.dugy.data.ChatRepository
import app.dugy.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppPhase { ONBOARDING, PIN_SETUP, LOCKED, UNLOCKED }

/**
 * يتحكم في حالة القفل. يُسجَّل مراقبًا لدورة حياة العملية (ProcessLifecycleOwner)،
 * فيُقفل التطبيق فور مغادرته أو بعد المهلة التي اختارها المستخدم.
 */
class LockManager(
    private val settings: SettingsStore,
    private val vault: Vault,
    private val repository: ChatRepository,
) : DefaultLifecycleObserver {

    private val _phase = MutableStateFlow(computePhase())
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable { lock() }
    private var backgroundedAt = 0L

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
    }

    fun lock() {
        handler.removeCallbacks(lockRunnable)
        if (_phase.value == AppPhase.UNLOCKED) {
            repository.close()
            vault.lockMemory()
            _phase.value = AppPhase.LOCKED
        }
    }

    /** مسح كل البيانات المحلية والمفاتيح، والعودة إلى البداية. */
    fun wipeEverything() {
        handler.removeCallbacks(lockRunnable)
        repository.close()
        repository.deleteFiles()
        vault.wipe()
        settings.clearAll()
        _phase.value = AppPhase.ONBOARDING
    }

    override fun onStop(owner: LifecycleOwner) {
        if (_phase.value != AppPhase.UNLOCKED) return
        val timeout = settings.current.autoLockMs
        if (timeout <= 0L) {
            lock()
        } else {
            backgroundedAt = SystemClock.elapsedRealtime()
            handler.postDelayed(lockRunnable, timeout)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(lockRunnable)
        // قد يُجمَّد التطبيق في الخلفية فلا يعمل المؤقت؛ نتحقق من المدة الفعلية عند العودة.
        if (_phase.value == AppPhase.UNLOCKED && backgroundedAt > 0L &&
            SystemClock.elapsedRealtime() - backgroundedAt >= settings.current.autoLockMs
        ) {
            lock()
        }
        backgroundedAt = 0L
    }
}
