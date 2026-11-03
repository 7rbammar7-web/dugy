package app.dugy.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dugy.AppContainer
import app.dugy.BuildConfig
import app.dugy.notify.Notifier
import app.dugy.security.BiometricAuth
import app.dugy.ui.components.ConfirmDialog
import app.dugy.ui.components.findFragmentActivity

private enum class PendingConfirm { WIPE_ALL, SIGN_OUT }

@Composable
fun SettingsTab(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onChangePin: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenContact: () -> Unit,
) {
    val context = LocalContext.current
    val biometricEnabled by container.vault.biometricEnabled.collectAsStateWithLifecycle()
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }
    val account by container.messenger.account.collectAsStateWithLifecycle()
    var notifGranted by remember { mutableStateOf(Notifier.hasPermission(context)) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifGranted = granted
        if (granted) toast("ستصلك تنبيهات الخواطر الجديدة.") else toast("لم يُمنح إذن التنبيهات.")
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        // ---- الحساب ----
        if (container.messenger.isConfigured) {
            SectionTitle("الحساب")
            if (account == null) {
                NavRow("إنشاء حساب أو تسجيل الدخول", "باسم مستخدم وكلمة مرور فقط", onOpenAccount)
            } else {
                ListItem(
                    headlineContent = { Text("مسجّل الدخول") },
                )
                ListItem(
                    modifier = Modifier.clickable { confirm = PendingConfirm.SIGN_OUT },
                    headlineContent = { Text("تسجيل الخروج من هذا الجهاز", color = MaterialTheme.colorScheme.error) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        // ---- القفل ----
        SectionTitle("قفل التطبيق")
        SwitchRow(
            title = "الفتح بالبصمة",
            subtitle = if (biometricAvailable) {
                "اختياري. يُعطَّل تلقائيًا إذا أُضيفت بصمة جديدة إلى الجهاز."
            } else {
                "غير متاح: لا توجد بصمة قوية مسجّلة على هذا الجهاز."
            },
            checked = biometricEnabled,
            enabled = biometricAvailable || biometricEnabled,
        ) { wantOn ->
            if (!wantOn) {
                container.vault.disableBiometric()
                return@SwitchRow
            }
            val activity = context.findFragmentActivity() ?: return@SwitchRow
            val cipher = try {
                container.vault.biometricEnrollCipher()
            } catch (e: Exception) {
                toast("تعذّر تجهيز البصمة على هذا الجهاز.")
                return@SwitchRow
            }
            BiometricAuth.authenticate(
                activity = activity,
                title = "تفعيل الفتح بالبصمة",
                subtitle = "أكّد بصمتك لربطها بقفل التطبيق",
                negativeText = "إلغاء",
                cipher = cipher,
                onSuccess = { authed ->
                    try {
                        container.vault.completeBiometricEnrollment(authed)
                    } catch (e: Exception) {
                        toast("تعذّر تفعيل البصمة.")
                    }
                },
                onError = { msg -> if (msg != null) toast(msg) },
            )
        }

        NavRow("تغيير رمز القفل", null, onChangePin)

        // ---- التنبيهات ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("التنبيهات")
            NavRow("تفعيل تنبيهات الخواطر الجديدة", null) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- تواصل معنا ----
        NavRow("تواصل معنا", "رأيك واقتراحاتك تهمّنا", onOpenContact)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- البيانات ----
        SectionTitle("البيانات")
        ListItem(
            modifier = Modifier.clickable { confirm = PendingConfirm.WIPE_ALL },
            headlineContent = { Text("حذف كل البيانات المحلية", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text("المفضلة والإعدادات ورمز القفل على هذا الجهاز.") },
        )
        Text(
            "الإصدار ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }

    when (confirm) {
        PendingConfirm.SIGN_OUT -> ConfirmDialog(
            title = "تسجيل الخروج؟",
            body = "ستحتاج إلى اسم المستخدم وكلمة المرور للدخول من جديد.",
            confirmText = "تسجيل الخروج",
            destructive = true,
            onConfirm = {
                container.vault.clearAccessSecret()
                container.messenger.signOut()
                confirm = null
            },
            onDismiss = { confirm = null },
        )
        PendingConfirm.WIPE_ALL -> ConfirmDialog(
            title = "حذف كل البيانات المحلية؟",
            body = "تُحذف كل البيانات والإعدادات ورمز القفل من هذا الجهاز. لا يمكن التراجع.",
            confirmText = "حذف كل شيء",
            destructive = true,
            onConfirm = {
                confirm = null
                container.lockManager.wipeEverything()
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) {
            { Text(subtitle) }
        } else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}

@Composable
private fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) {
            { Text(subtitle) }
        } else null,
        // أيقونة معكوسة تلقائيًا: تشير إلى اليسار في الواجهة العربية.
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}
