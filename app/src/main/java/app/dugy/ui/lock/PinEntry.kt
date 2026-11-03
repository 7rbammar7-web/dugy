package app.dugy.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

const val PIN_MIN = 6
const val PIN_MAX = 12

/**
 * إدخال رمز القفل بلوحة أرقام داخلية (لا تمر الأرقام عبر لوحة مفاتيح خارجية قد تتعلّم منها).
 * الأرقام تُحفظ في CharArray وتُصفَّر بعد الإرسال، بدل String غير قابل للمسح.
 */
@Composable
fun PinEntry(
    title: String,
    subtitle: String?,
    error: String?,
    busy: Boolean,
    enabled: Boolean,
    onSubmit: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val buffer = remember { CharArray(PIN_MAX) }
    var length by remember { mutableIntStateOf(0) }
    val canType = enabled && !busy

    fun submit() {
        if (length < PIN_MIN || !canType) return
        val pin = buffer.copyOf(length)
        buffer.fill('\u0000')
        length = 0
        onSubmit(pin)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))

        PinDots(length = length)

        Box(Modifier.height(56.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(28.dp))
                error != null -> Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // لوحة الأرقام تبقى بترتيب الهاتف المعتاد (يسار إلى يمين) حتى في الواجهة العربية.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9')).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { d ->
                            PadKey(label = d.toString(), enabled = canType && length < PIN_MAX) {
                                buffer[length] = d
                                length += 1
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PadIconKey(
                        enabled = canType && length > 0,
                        description = "حذف رقم",
                        onClick = {
                            length -= 1
                            buffer[length] = '\u0000'
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null) }
                    PadKey(label = "0", enabled = canType && length < PIN_MAX) {
                        buffer[length] = '0'
                        length += 1
                    }
                    PadIconKey(
                        enabled = canType && length >= PIN_MIN,
                        description = "تأكيد",
                        primary = true,
                        onClick = { submit() },
                    ) { Icon(Icons.Filled.Check, contentDescription = null) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        extra()
    }
}

@Composable
private fun PinDots(length: Int) {
    val slots = maxOf(PIN_MIN, length)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics { contentDescription = "عدد الأرقام المُدخلة $length" },
        ) {
            repeat(slots) { i ->
                val filled = i < length
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (filled) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun PadKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun PadIconKey(
    enabled: Boolean,
    description: String,
    primary: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val bg = when {
        primary && enabled -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides when {
                !enabled -> MaterialTheme.colorScheme.outline
                primary -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            }
        ) { content() }
    }
}
