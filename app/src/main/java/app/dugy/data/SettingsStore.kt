package app.dugy.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NotificationPreview {
    /** الافتراضي: «لديك رسالة جديدة» فقط. */
    HIDDEN,
    SENDER_ONLY,
    /** لا يُفعَّل إلا باختيار صريح وتأكيد من المستخدم. */
    FULL,
}

data class AppSettings(
    val onboardingDone: Boolean,
    val autoLockMs: Long,
    val notificationPreview: NotificationPreview,
    val secureScreen: Boolean,
    val wipeAfterFailures: Boolean,
    val favoriteQuotes: Set<Int>,
)

/** إعدادات غير سرية. الملف مستبعد من النسخ الاحتياطي مع بقية بيانات التطبيق. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun read() = AppSettings(
        onboardingDone = prefs.getBoolean(K_ONBOARDING, false),
        autoLockMs = prefs.getLong(K_AUTOLOCK, 0L),
        notificationPreview = prefs.getString(K_NOTIF, null)
            ?.let { name -> NotificationPreview.entries.firstOrNull { it.name == name } }
            ?: NotificationPreview.HIDDEN,
        secureScreen = prefs.getBoolean(K_SECURE, true),
        wipeAfterFailures = prefs.getBoolean(K_WIPE, false),
        favoriteQuotes = (prefs.getStringSet(K_FAVS, null) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }
            .toSet(),
    )

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
        _state.value = read()
    }

    fun setOnboardingDone(done: Boolean) = edit { putBoolean(K_ONBOARDING, done) }
    fun setAutoLockMs(ms: Long) = edit { putLong(K_AUTOLOCK, ms) }
    fun setNotificationPreview(p: NotificationPreview) = edit { putString(K_NOTIF, p.name) }
    fun setSecureScreen(on: Boolean) = edit { putBoolean(K_SECURE, on) }
    fun setWipeAfterFailures(on: Boolean) = edit { putBoolean(K_WIPE, on) }

    fun toggleFavorite(id: Int) {
        val favs = current.favoriteQuotes.toMutableSet()
        if (!favs.add(id)) favs.remove(id)
        edit { putStringSet(K_FAVS, favs.map { it.toString() }.toSet()) }
    }

    fun clearAll() {
        prefs.edit().clear().commit()
        _state.value = read()
    }

    companion object {
        val AUTOLOCK_OPTIONS: List<Pair<Long, String>> = listOf(
            0L to "فورًا عند مغادرة التطبيق",
            60_000L to "بعد دقيقة",
            300_000L to "بعد ٥ دقائق",
        )

        private const val K_ONBOARDING = "onboarding_done"
        private const val K_AUTOLOCK = "autolock_ms"
        private const val K_NOTIF = "notif_preview"
        private const val K_SECURE = "secure_screen"
        private const val K_WIPE = "wipe_after_failures"
        private const val K_FAVS = "fav_quotes"
    }
}
