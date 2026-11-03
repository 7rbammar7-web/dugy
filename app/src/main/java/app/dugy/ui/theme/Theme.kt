package app.dugy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

// هوية DUGY: نيلي عميق للثقة والهدوء، وأخضر مائي للتأكيد والتوثيق.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2B2D6E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF14154A),
    secondary = Color(0xFF0F8C78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8F1E8),
    onSecondaryContainer = Color(0xFF00382F),
    tertiaryContainer = Color(0xFFFFEFD2),
    onTertiaryContainer = Color(0xFF4A3300),
    background = Color(0xFFF6F6FB),
    onBackground = Color(0xFF1A1B24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B24),
    surfaceVariant = Color(0xFFE5E5F0),
    onSurfaceVariant = Color(0xFF464757),
    outline = Color(0xFFB7B8C9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC0C1FF),
    onPrimary = Color(0xFF1B1C5A),
    primaryContainer = Color(0xFF3A3C85),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFF6EDBC5),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0E5245),
    onSecondaryContainer = Color(0xFFC8F1E8),
    tertiaryContainer = Color(0xFF4A3A14),
    onTertiaryContainer = Color(0xFFFFE6B0),
    background = Color(0xFF0E0F1C),
    onBackground = Color(0xFFE4E4F2),
    surface = Color(0xFF14152A),
    onSurface = Color(0xFFE4E4F2),
    surfaceVariant = Color(0xFF24253D),
    onSurfaceVariant = Color(0xFFC3C3D6),
    outline = Color(0xFF45465E),
)

private val base = Typography()

// ارتفاع أسطر أكبر قليلًا لراحة قراءة العربية.
private val AppTypography = base.copy(
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 28.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp),
    titleLarge = base.titleLarge.copy(lineHeight = 32.sp),
)

@Composable
fun DugyTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography) {
        // التطبيق عربي أولًا: اتجاه RTL بغض النظر عن لغة الجهاز.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
