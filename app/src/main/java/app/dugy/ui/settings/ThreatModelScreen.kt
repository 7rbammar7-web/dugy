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
                    "تشفير طرفي للرسائل بمكتبة Signal الرسمية (libsignal 0.106.1): بروتوكول PQXDH المقاوم للحواسيب الكمية لبدء الجلسة، ثم Double Ratchet لكل رسالة.",
                    "سرية أمامية: كشف مفتاح اليوم لا يكشف رسائل الأمس.",
                    "الخادم يستقبل رسائل مشفّرة فقط ويحذفها فور استلامها، ولا يملك مفاتيح فكها.",
                    "رمز أمان من 60 رقمًا تقارنه مع جهة الاتصال، وتحذير واضح يوقف الإرسال إذا تغيّر مفتاحها.",
                    "حشو الرسائل إلى أطوال ثابتة حتى لا يكشف حجمها المشفّر طولها الدقيق.",
                    "الرسائل ومفاتيح التشفير محفوظة على الجهاز مشفّرة بـ AES-256-GCM بمفتاح محمي برمز القفل وAndroid Keystore.",
                    "قفل تلقائي يمحو المفاتيح والرسائل من ذاكرة التطبيق، وتأخير متصاعد بعد المحاولات الخاطئة.",
                    "منع لقطات الشاشة والتسجيل، واستبعاد البيانات من النسخ الاحتياطي، وإشعارات لا تكشف المحتوى افتراضيًا.",
                    "لا تحليلات ولا تقارير أعطال ولا سجلات تحتوي محتوى الرسائل.",
                ),
            )
            Section(
                "ما يراه الخادم رغم التشفير (البيانات الوصفية)",
                listOf(
                    "أسماء المستخدمين في التطبيق.",
                    "من يراسل من، ومتى، وعدد الرسائل وحجمها التقريبي.",
                    "عنوان الشبكة (IP) لكل جهاز يتصل، ونوع الجهاز بحسب سجلات Google.",
                    "الخادم مستضاف على Firebase من Google، ويخضع لسياساتها وقوانين الدول التي تعمل فيها.",
                ),
            )
            Section(
                "ما لا يحميه أي تطبيق، ومنه هذا التطبيق",
                listOf(
                    "جهاز مخترق ببرمجية تجسس أو نظام تشغيل معدّل أو خبيث.",
                    "هاتف أو تطبيق مفتوح بيد شخص آخر، أو إجبارك على فتحه.",
                    "تصوير الشاشة بكاميرا أخرى، أو نسخ المستلم للرسائل وإعادة إرسالها.",
                    "لوحات المفاتيح الخارجية التي قد تتعلّم مما تكتبه.",
                    "انتحال جهة اتصال إذا لم تطابق رمز الأمان معها عبر قناة أخرى.",
                    "أخطاء في التنفيذ لم تُكتشف بعد.",
                ),
            )
            Section(
                "حدود هذا الإصدار",
                listOf(
                    "تصل الرسائل والتطبيق مفتوح أو في الخلفية غير مقفل؛ لا توجد إشعارات دفع والتطبيق مغلق أو مقفل، وتنتظر الرسائل على الخادم مشفّرة.",
                    "جهاز واحد لكل حساب: الدخول من جهاز جديد يولّد مفاتيح جديدة، ولا تُنقل المحادثات القديمة.",
                    "لا يوجد استرجاع لكلمة المرور.",
                    "الرسائل المؤقتة تُحذف من هذا الجهاز فقط، لا من جهاز الطرف الآخر.",
                    "لم يخضع التطبيق لمراجعة أمنية مستقلة؛ لا تعتمد عليه في حالات تهدد الحياة.",
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
