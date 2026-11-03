package app.dugy.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * إعدادات غير سرية. الملف مستبعد من النسخ الاحتياطي مع بقية بيانات التطبيق.
 *
 * ثابتة دائمًا وليست خيارات للمستخدم: القفل فور مغادرة التطبيق، ومنع لقطات الشاشة،
 * وإخفاء كل التفاصيل في الإشعارات، ومسح البيانات بعد عشر محاولات فاشلة.
 */
data class AppSettings(
    val onboardingDone: Boolean,
    val favoriteQuotes: Set<Int>,
    val notificationsAsked: Boolean,
)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun read() = AppSettings(
        onboardingDone = prefs.getBoolean(K_ONBOARDING, false),
        favoriteQuotes = (prefs.getStringSet(K_FAVS, null) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }
            .toSet(),
        notificationsAsked = prefs.getBoolean(K_NOTIF_ASKED, false),
    )

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
        _state.value = read()
    }

    fun setOnboardingDone(done: Boolean) = edit { putBoolean(K_ONBOARDING, done) }
    fun setNotificationsAsked() = edit { putBoolean(K_NOTIF_ASKED, true) }

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
        private const val K_ONBOARDING = "onboarding_done"
        private const val K_FAVS = "fav_quotes"
        private const val K_NOTIF_ASKED = "notif_asked"
    }
}
