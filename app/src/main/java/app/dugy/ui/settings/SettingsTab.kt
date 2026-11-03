package app.dugy.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.dugy.data.NotificationPreview
import app.dugy.data.SettingsStore
import app.dugy.notify.Notifier
import app.dugy.security.BiometricAuth
import app.dugy.security.KeystoreCipher
import app.dugy.security.Vault
import app.dugy.ui.components.ConfirmDialog
import app.dugy.ui.components.findFragmentActivity

private enum class PendingConfirm { FULL_PREVIEW, DISABLE_SECURE, ENABLE_WIPE, WIPE_ALL }

@Composable
fun SettingsTab(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onOpenThreatModel: () -> Unit,
    onChangePin: () -> Unit,
) {
    val context = LocalContext.current
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val biometricEnabled by container.vault.biometricEnabled.collectAsStateWithLifecycle()
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun showTestNotification() {
        val shown = Notifier.showMessage(
            context,
            settings.notificationPreview,
            senderName = "جهة اتصال تجريبية",
            text = "هذا نص تجريبي لمعاينة شكل الإشعار.",
            isTest = true,
        )
        if (!shown) toast("الإشعارات معطّلة لهذا التطبيق من إعدادات الجهاز.")
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showTestNotification() else toast("لم يُمنح إذن الإشعارات.")
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        // ---- حالة الإصدار ----
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("حالة هذا الإصدار", style = MaterialTheme.typography.titleSmall)
                Text(
                    "نموذج أولي — لا يرسل رسائل مشفرة فعليًا. لا يملك التطبيق إذن الوصول إلى الإنترنت، " +
                        "والرسائل تُحفظ مشفّرة على هذا الجهاز فقط. غير صالح لمحادثات حساسة.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val storage = when (KeystoreCipher.masterIsStrongBox(context)) {
                    true -> "StrongBox (شريحة أمان مستقلة)"
                    false -> "Android Keystore (بيئة التنفيذ الآمنة)"
                    null -> "غير مُنشأ بعد"
                }
                Text("مخزن المفاتيح: $storage", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---- القفل ----
        SectionTitle("قفل التطبيق")
        Text(
            "القفل التلقائي",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SettingsStore.AUTOLOCK_OPTIONS.forEach { (ms, label) ->
            RadioRow(label, selected = settings.autoLockMs == ms) { container.settings.setAutoLockMs(ms) }
        }

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
                toast("تعذّر تجهيز مفتاح البصمة على هذا الجهاز.")
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

        SwitchRow(
            title = "مسح البيانات بعد ${Vault.WIPE_THRESHOLD} محاولات فاشلة",
            subtitle = "معطّل افتراضيًا. عند تفعيله تُمسح كل المحادثات والمفاتيح على هذا الجهاز نهائيًا.",
            checked = settings.wipeAfterFailures,
        ) { wantOn ->
            if (wantOn) confirm = PendingConfirm.ENABLE_WIPE else container.settings.setWipeAfterFailures(false)
        }
        Text(
            "بعد ${Vault.FREE_ATTEMPTS} محاولات خاطئة يُفرض انتظار يبدأ بـ ٣٠ ثانية ويتضاعف مع كل خطأ إضافي.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- الإشعارات ----
        SectionTitle("الإشعارات")
        RadioRow("إخفاء كل التفاصيل (الافتراضي)", settings.notificationPreview == NotificationPreview.HIDDEN) {
            container.settings.setNotificationPreview(NotificationPreview.HIDDEN)
        }
        RadioRow("إظهار اسم المرسل فقط", settings.notificationPreview == NotificationPreview.SENDER_ONLY) {
            container.settings.setNotificationPreview(NotificationPreview.SENDER_ONLY)
        }
        RadioRow("إظهار اسم المرسل ونص الرسالة", settings.notificationPreview == NotificationPreview.FULL) {
            confirm = PendingConfirm.FULL_PREVIEW
        }
        Text(
            "على شاشة القفل تظهر دائمًا عبارة «لديك رسالة جديدة» فقط، مهما كان اختيارك هنا.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        OutlinedButton(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifier.hasPermission(context)) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    showTestNotification()
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text("عرض إشعار تجريبي") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- لقطات الشاشة ----
        SectionTitle("لقطات الشاشة والتسجيل")
        SwitchRow(
            title = "منع لقطات الشاشة والتسجيل",
            subtitle = "يمنع أيضًا ظهور المحتوى في شاشة التطبيقات الأخيرة. " +
                "لا يمنع تصوير الشاشة بكاميرا أخرى، ولا يحمي على جهاز معدّل أو مخترق.",
            checked = settings.secureScreen,
        ) { wantOn ->
            if (wantOn) container.settings.setSecureScreen(true) else confirm = PendingConfirm.DISABLE_SECURE
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- النسخ الاحتياطي ----
        SectionTitle("النسخ الاحتياطي")
        Text(
            "بيانات التطبيق مستبعدة من النسخ الاحتياطي السحابي (Google) ومن النقل بين الأجهزة. " +
                "لا تُرفع الرسائل أو المفاتيح إلى Google Drive أو أي تخزين سحابي. " +
                "نتيجة ذلك: عند تغيير الهاتف أو حذف التطبيق لا يمكن استعادة المحادثات. " +
                "قد تتصرف أدوات النقل الخاصة ببعض الشركات المصنّعة بشكل مختلف.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- الحذف والمعلومات ----
        SectionTitle("البيانات والمعلومات")
        NavRow("حدود الحماية", "ما الذي يحميه التطبيق وما الذي لا يحميه", onOpenThreatModel)
        ListItem(
            modifier = Modifier.clickable { confirm = PendingConfirm.WIPE_ALL },
            headlineContent = { Text("حذف كل البيانات المحلية", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text("المحادثات والإعدادات والمفاتيح على هذا الجهاز فقط.") },
        )
        Text(
            "الإصدار ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }

    when (confirm) {
        PendingConfirm.FULL_PREVIEW -> ConfirmDialog(
            title = "إظهار نص الرسائل في الإشعارات؟",
            body = "سيظهر اسم المرسل ونص الرسالة في لوحة الإشعارات عندما يكون الهاتف مفتوحًا، " +
                "وقد يراه من ينظر إلى شاشتك أو تطبيقات لديها صلاحية قراءة الإشعارات. " +
                "على شاشة القفل يبقى الإشعار عامًا.",
            confirmText = "إظهار النص",
            onConfirm = {
                container.settings.setNotificationPreview(NotificationPreview.FULL)
                confirm = null
            },
            onDismiss = { confirm = null },
        )
        PendingConfirm.DISABLE_SECURE -> ConfirmDialog(
            title = "السماح بلقطات الشاشة؟",
            body = "سيصبح بإمكانك وبإمكان التطبيقات الأخرى التقاط صور أو تسجيلات لشاشة المحادثات، " +
                "وستظهر معاينتها في شاشة التطبيقات الأخيرة.",
            confirmText = "السماح",
            destructive = true,
            onConfirm = {
                container.settings.setSecureScreen(false)
                confirm = null
            },
            onDismiss = { confirm = null },
        )
        PendingConfirm.ENABLE_WIPE -> ConfirmDialog(
            title = "تفعيل المسح التلقائي؟",
            body = "إذا أُدخل رمز خاطئ ${Vault.WIPE_THRESHOLD} مرات متتالية، تُمسح كل المحادثات والمفاتيح على هذا الجهاز " +
                "نهائيًا ودون إمكانية استعادة. قد يحدث ذلك أيضًا إذا عبث أحد بهاتفك أو أخطأت أنت عدة مرات.",
            confirmText = "فهمت، فعّل المسح",
            destructive = true,
            onConfirm = {
                container.settings.setWipeAfterFailures(true)
                confirm = null
            },
            onDismiss = { confirm = null },
        )
        PendingConfirm.WIPE_ALL -> ConfirmDialog(
            title = "حذف كل البيانات المحلية؟",
            body = "تُحذف كل المحادثات والإعدادات ورمز القفل ومفاتيح التشفير من هذا الجهاز. " +
                "تُحذف المفاتيح من Android Keystore فيصبح أي أثر متبقٍ من الملف المشفّر غير قابل للفك. " +
                "لا يمكن التراجع.",
            confirmText = "حذف كل شيء",
            destructive = true,
            onConfirm = {
                confirm = null
                Notifier.cancelAll(context)
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
