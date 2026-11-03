package app.dugy.data

/**
 * نقطة الربط مع خدمة الترحيل.
 *
 * عقد إلزامي لأي تنفيذ حقيقي (المرحلة الثانية):
 *  1. يُشفَّر النص على هذا الجهاز بجلسة بروتوكول طرفي معتمد قبل أي اتصال شبكي.
 *  2. لا يغادر النص الصريح أو المفاتيح الخاصة الجهاز، ولا تُكتب في السجلات.
 *  3. يُستخدم messageId كمفتاح عدم تكرار (idempotency) عند إعادة المحاولة.
 */
interface MessageTransport {
    /** هل يرسل هذا التنفيذ فعلًا بتشفير طرفي؟ تعتمد عليه الواجهة لعرض التحذيرات. */
    val isEndToEndEncrypted: Boolean

    suspend fun send(recipient: Contact, messageId: String, plaintext: String): MessageStatus
}

/**
 * تنفيذ النموذج الأولي: لا يرسل شيئًا. التطبيق لا يملك إذن الإنترنت أصلًا.
 * تبقى الرسالة محفوظة مشفّرة على هذا الجهاز فقط.
 */
class PrototypeTransport : MessageTransport {
    override val isEndToEndEncrypted: Boolean = false

    override suspend fun send(recipient: Contact, messageId: String, plaintext: String): MessageStatus =
        MessageStatus.LOCAL_ONLY
}
