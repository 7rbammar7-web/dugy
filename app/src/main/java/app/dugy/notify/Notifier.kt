package app.dugy.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dugy.MainActivity
import app.dugy.R
import app.dugy.data.NotificationPreview

/**
 * إشعارات الرسائل.
 * - على شاشة القفل تظهر دائمًا نسخة عامة: «لديك رسالة جديدة» (publicVersion).
 * - داخل الإشعار نفسه يُحترم اختيار المستخدم، والافتراضي إخفاء كل التفاصيل.
 * - لا يُوضع أي محتوى في extras أو بيانات غير مرئية.
 */
object Notifier {
    private const val CHANNEL_ID = "messages"
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "الرسائل", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "إشعارات الرسائل الواردة. لا تعرض المحتوى افتراضيًا."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun showMessage(
        context: Context,
        preview: NotificationPreview,
        senderName: String?,
        text: String?,
        isTest: Boolean,
    ): Boolean {
        if (!hasPermission(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("DUGY")
            .setContentText("لديك رسالة جديدة")
            .build()

        val (title, body) = when (preview) {
            NotificationPreview.HIDDEN -> "DUGY" to "لديك رسالة جديدة"
            NotificationPreview.SENDER_ONLY -> (senderName ?: "DUGY") to "رسالة جديدة"
            NotificationPreview.FULL -> (senderName ?: "DUGY") to (text ?: "رسالة جديدة")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        if (isTest) builder.setSubText("إشعار تجريبي")

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        return true
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}
