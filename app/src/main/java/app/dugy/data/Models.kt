package app.dugy.data

data class Contact(
    val id: String,
    val name: String,
    /** معرّف جهة الاتصال كما أدخله المستخدم. في المرحلة الثانية: معرّف حساب أو رمز دعوة موثّق. */
    val handle: String,
)

enum class MessageStatus {
    /** قيد المعالجة على الجهاز. */
    PENDING,
    /** وصلت إلى خدمة الترحيل (المرحلة الثانية). */
    SENT,
    /** أكّد جهاز المستلم الاستلام (المرحلة الثانية). */
    DELIVERED,
    FAILED,
    /** النموذج الأولي: حُفظت مشفّرة على هذا الجهاز ولم تُرسل إلى أي مكان. */
    LOCAL_ONLY,
}

data class Message(
    val id: String,
    val outgoing: Boolean,
    val text: String,
    val createdAt: Long,
    val status: MessageStatus,
)

data class Conversation(
    val id: String,
    val contact: Contact,
    val createdAt: Long,
    /** مدة الرسائل المؤقتة بالمللي ثانية، أو null إذا كانت معطّلة. */
    val retentionMs: Long?,
    val messages: List<Message>,
) {
    val lastActivity: Long get() = messages.lastOrNull()?.createdAt ?: createdAt
}

object Retention {
    const val DAY = 24L * 60 * 60 * 1000
    const val WEEK = 7 * DAY
    const val MONTH = 30 * DAY

    val options: List<Pair<Long?, String>> = listOf(
        null to "معطّلة",
        DAY to "يوم واحد",
        WEEK to "أسبوع",
        MONTH to "شهر",
    )

    fun label(ms: Long?): String = options.firstOrNull { it.first == ms }?.second ?: "مخصّصة"
}
