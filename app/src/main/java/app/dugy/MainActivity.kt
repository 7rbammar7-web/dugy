package app.dugy

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.dugy.ui.DugyRoot
import app.dugy.ui.theme.DugyTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as DugyApp).container

        // يُطبَّق قبل رسم أول إطار حتى لا تظهر أي لقطة في شاشة التطبيقات الأخيرة.
        applySecureFlag(container.settings.current.secureScreen)

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                container.settings.state
                    .map { it.secureScreen }
                    .distinctUntilChanged()
                    .collect { applySecureFlag(it) }
            }
        }

        setContent {
            DugyTheme {
                DugyRoot(container)
            }
        }
    }

    /**
     * FLAG_SECURE يمنع لقطات الشاشة وتسجيلها وعرضها على شاشات غير آمنة، ويخفي
     * معاينة التطبيق في شاشة التطبيقات الأخيرة. لا يمنع التصوير بكاميرا خارجية.
     */
    private fun applySecureFlag(secure: Boolean) {
        if (secure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!secure)
        }
    }
}
