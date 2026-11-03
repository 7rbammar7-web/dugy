@file:OptIn(ExperimentalMaterial3Api::class)

package app.dugy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ThreatModelScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("حدود الحماية") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section(
                "ما يحميه هذا الإصدار",
                listOf(
                    "الرسائل المحفوظة على الجهاز مشفّرة بـ AES-256-GCM بمفتاح عشوائي.",
                    "مفتاح البيانات مغلّف برمز القفل ثم بمفتاح داخل Android Keystore، فلا يمكن تخمين الرمز خارج هذا الجهاز.",
                    "قفل تلقائي يمحو المفتاح والرسائل من ذاكرة التطبيق عند المغادرة أو بعد المهلة المختارة.",
                    "تأخير متصاعد بعد المحاولات الخاطئة، ومسح اختياري بعد عدد محدد.",
                    "منع لقطات الشاشة والتسجيل وإخفاء المعاينة في شاشة التطبيقات الأخيرة.",
                    "استبعاد البيانات من النسخ الاحتياطي السحابي والنقل بين الأجهزة.",
                    "إشعارات عامة لا تكشف المرسل أو النص افتراضيًا.",
                    "لا تحليلات ولا تقارير أعطال ولا سجلات تحتوي محتوى الرسائل، ولا إذن إنترنت.",
                ),
            )
            Section(
                "ما لا يحميه أي تطبيق، ومنه هذا التطبيق",
                listOf(
                    "جهاز مخترق ببرمجية تجسس أو نظام تشغيل معدّل أو خبيث.",
                    "هاتف أو تطبيق مفتوح بيد شخص آخر.",
                    "سرقة رمز القفل أو إجبارك على فتح الجهاز.",
                    "تصوير الشاشة بكاميرا أخرى، أو نسخ المستلم للرسائل وإعادة إرسالها.",
                    "لوحات المفاتيح الخارجية التي قد تتعلّم مما تكتبه في خانة الرسائل.",
                    "بقايا مؤقتة للنص في ذاكرة الجهاز أثناء فتح التطبيق.",
                    "أخطاء في التنفيذ أو مكتبات غير محدّثة.",
                ),
            )
            Section(
                "ما لم يُنفّذ بعد في هذا النموذج",
                listOf(
                    "إرسال الرسائل واستقبالها عبر الإنترنت بتشفير طرفي حقيقي (Forward Secrecy).",
                    "التحقق من هوية جهات الاتصال برمز أمان أو QR مع تنبيه عند تغيّر المفتاح.",
                    "إدارة الأجهزة المرتبطة وإلغاء جهاز مفقود.",
                    "حذف الرسائل من الخادم تلقائيًا بعد مدة الاحتفاظ.",
                    "مراجعة أمنية مستقلة.",
                ),
            )
            Section(
                "ما سيراه الخادم في النسخة الكاملة",
                listOf(
                    "لن يرى نص الرسائل ولا مفاتيح فكها.",
                    "قد يرى: معرّفات الحسابات، ووقت الاتصال، وحجم الرسائل المشفّرة، وعنوان الشبكة، بحسب البنية وسياسات التسجيل.",
                    "ستُوثَّق هذه البيانات الوصفية ومدة الاحتفاظ بها في سياسة الخصوصية قبل الإطلاق.",
                ),
            )
            Text(
                "لا يوجد تطبيق «غير قابل للاختراق». الهدف تقليل المخاطر بصدق، لا الوعد بحماية مطلقة.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Section(title: String, items: List<String>) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    items.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
}
