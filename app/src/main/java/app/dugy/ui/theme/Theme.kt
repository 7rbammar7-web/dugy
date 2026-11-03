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

// هوية مستقلة: أزرق ليلي للثقة، أخضر مائي للتأكيد، خلفية فاتحة مريحة للقراءة.
private val LightColors = lightColorScheme(
    primary = Color(0xFF14365C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4F5),
    onPrimaryContainer = Color(0xFF0A1E35),
    secondary = Color(0xFF1F8A7E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEEE9),
    onSecondaryContainer = Color(0xFF00332D),
    tertiaryContainer = Color(0xFFFFF1D6),
    onTertiaryContainer = Color(0xFF4A3300),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF151B23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151B23),
    surfaceVariant = Color(0xFFE6EBF1),
    onSurfaceVariant = Color(0xFF44505E),
    outline = Color(0xFFB5C0CC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C8EE),
    onPrimary = Color(0xFF0A2440),
    primaryContainer = Color(0xFF1E3D63),
    onPrimaryContainer = Color(0xFFD6E4F5),
    secondary = Color(0xFF6FD3C5),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF0F4F47),
    onSecondaryContainer = Color(0xFFCDEEE9),
    tertiaryContainer = Color(0xFF4A3A14),
    onTertiaryContainer = Color(0xFFFFE6B0),
    background = Color(0xFF0B1624),
    onBackground = Color(0xFFE3E9F0),
    surface = Color(0xFF0F1D2E),
    onSurface = Color(0xFFE3E9F0),
    surfaceVariant = Color(0xFF1C2B3E),
    onSurfaceVariant = Color(0xFFB7C3D1),
    outline = Color(0xFF3C4C60),
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
