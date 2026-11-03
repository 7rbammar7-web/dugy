package app.dugy.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import app.dugy.R

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painterResource(R.drawable.dugy_logo),
                contentDescription = null,
                modifier = Modifier.size(88.dp).align(Alignment.CenterHorizontally),
            )
            Text(
                "DUGY — مراسلة خاصة",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Text(
                "تطبيق يجمع قسمين واضحين ومنفصلين:",
                style = MaterialTheme.typography.bodyLarge,
            )
            Feature(Icons.Outlined.AutoStories, "الخواطر", "خواطر واقتباسات قصيرة تقرؤها وتحفظ ما يعجبك منها.")
            Feature(
                Icons.Outlined.Forum,
                "المحادثات الخاصة",
                "قسم ظاهر لمراسلة أصدقائك برسائل مشفّرة تشفيرًا طرفيًا بمكتبة Signal: لا يقرؤها إلا هاتف المستلم.",
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Shield, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("حدود الخصوصية باختصار", style = MaterialTheme.typography.titleMedium)
                    }
                    Bullet("التطبيق يُقفل برمز تختاره، ويمكن إضافة البصمة.")
                    Bullet("الرسائل تُشفَّر على هاتفك قبل إرسالها، والخادم لا يملك مفاتيح فكها.")
                    Bullet("الخادم يرى اسم المستخدم ومن تراسل ومتى، لكنه لا يرى نص الرسائل.")
                    Bullet("الرسائل تُحفظ على جهازك مشفّرة، ولا تدخل في النسخ الاحتياطي السحابي.")
                    Bullet("لا تحمي أي حماية من جهاز مخترق أو هاتف مفتوح بيد شخص آخر.")
                    Bullet("منع لقطات الشاشة لا يمنع تصوير الشاشة بكاميرا أخرى.")
                    Bullet("ما يصل إلى المستلم قد يحفظه أو ينسخه؛ والحذف لا يضمن محو نسخه.")
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("إصدار تجريبي", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "التشفير يعتمد على مكتبة Signal المعروفة، لكن التطبيق نفسه جديد ولم يخضع بعد لمراجعة أمنية مستقلة. " +
                            "لا تعتمد عليه وحده في المواقف التي تهدد السلامة.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("فهمت، أنشئ رمز القفل")
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium)
}
