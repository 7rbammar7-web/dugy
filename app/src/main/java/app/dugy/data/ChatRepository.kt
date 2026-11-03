package app.dugy.data

import android.content.Context
import app.dugy.security.AesGcm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * مخزن المحادثات المحلي. الملف على القرص مشفّر دائمًا بمفتاح البيانات (AES-256-GCM).
 * النص الصريح لا يوجد إلا في الذاكرة أثناء فتح التطبيق، ويُمسح من الحالة عند القفل.
 */
class ChatRepository(context: Context, private val scope: CoroutineScope) {
    private val dir = context.applicationContext.filesDir
    private val file = File(dir, "chats.bin")
    private val tmp = File(dir, "chats.bin.tmp")

    /** خيط واحد لكل عمليات القرص، فتُنفَّذ الكتابات بترتيب طلبها ولا تتداخل. */
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _loadError = MutableStateFlow(false)
    /** true إذا تعذّر فك الملف؛ نمتنع حينها عن الكتابة كي لا نستبدل بيانات قائمة. */
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

    @Volatile
    private var key: ByteArray? = null

    suspend fun open(dataKey: ByteArray) {
        val k = dataKey.copyOf()
        val loaded = withContext<List<Conversation>?>(io) {
            if (!file.exists()) return@withContext emptyList<Conversation>()
            try {
                StoreCodec.decode(AesGcm.decrypt(k, file.readBytes(), AAD))
            } catch (e: Exception) {
                null
            }
        }
        key = k
        _loadError.value = loaded == null
        _conversations.value = loaded ?: emptyList()
        purgeExpired()
    }

    fun close() {
        key?.fill(0)
        key = null
        _conversations.value = emptyList()
    }

    /** يحذف الملف بعد إنهاء أي كتابة معلّقة (الترتيب مضمون عبر خيط القرص الواحد). */
    fun deleteFiles() {
        scope.launch(io) {
            file.delete()
            tmp.delete()
        }
    }

    // ---------------- العمليات ----------------

    fun conversation(id: String): Conversation? = _conversations.value.firstOrNull { it.id == id }

    /** محادثة جهة الاتصال صاحبة اسم المستخدم [username]، أو محادثة جديدة إن لم توجد. */
    fun findOrCreateConversation(username: String, uid: String, displayName: String = username): String? {
        if (key == null) return null
        _conversations.value.firstOrNull { it.contact.handle == username }?.let { existing ->
            if (existing.contact.uid != uid && uid.isNotEmpty()) {
                mutate { list ->
                    list.map { if (it.id == existing.id) it.copy(contact = it.contact.copy(uid = uid)) else it }
                }
            }
            return existing.id
        }
        return createConversation(displayName, username, uid)
    }

    /** يضيف رسالة واردة بعد فك تشفيرها. يتجاهل التكرار بنفس المعرّف. */
    fun addIncoming(username: String, uid: String, messageId: String, text: String, at: Long, failed: Boolean): String? {
        val convId = findOrCreateConversation(username, uid) ?: return null
        val exists = conversation(convId)?.messages?.any { it.id == messageId } == true
        if (exists) return convId
        val msg = Message(
            id = messageId,
            outgoing = false,
            text = text,
            createdAt = at,
            status = if (failed) MessageStatus.FAILED else MessageStatus.RECEIVED,
        )
        mutate { list ->
            list.map { c ->
                if (c.id != convId) c else c.copy(messages = (c.messages + msg).sortedBy { it.createdAt })
            }
        }
        return convId
    }

    fun createConversation(name: String, handle: String, uid: String = ""): String? {
        if (key == null) return null
        val now = System.currentTimeMillis()
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            contact = Contact(UUID.randomUUID().toString(), name.trim(), handle.trim(), uid),
            createdAt = now,
            retentionMs = null,
            messages = emptyList(),
        )
        mutate { it + conv }
        return conv.id
    }

    fun addOutgoing(conversationId: String, text: String): Message? {
        if (key == null) return null
        val msg = Message(
            id = UUID.randomUUID().toString(),
            outgoing = true,
            text = text,
            createdAt = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
        )
        mutate { list ->
            list.map { if (it.id == conversationId) it.copy(messages = it.messages + msg) else it }
        }
        return msg
    }

    fun updateStatus(conversationId: String, messageId: String, status: MessageStatus) = mutate { list ->
        list.map { c ->
            if (c.id != conversationId) c
            else c.copy(messages = c.messages.map { if (it.id == messageId) it.copy(status = status) else it })
        }
    }

    fun deleteMessage(conversationId: String, messageId: String) = mutate { list ->
        list.map { c ->
            if (c.id != conversationId) c else c.copy(messages = c.messages.filterNot { it.id == messageId })
        }
    }

    fun deleteConversation(conversationId: String) = mutate { list -> list.filterNot { it.id == conversationId } }

    fun setRetention(conversationId: String, retentionMs: Long?) {
        mutate { list -> list.map { if (it.id == conversationId) it.copy(retentionMs = retentionMs) else it } }
        purgeExpired()
    }

    /** يحذف من هذا الجهاز الرسائل التي تجاوزت مدة الاحتفاظ. */
    fun purgeExpired() {
        val now = System.currentTimeMillis()
        val current = _conversations.value
        val needsPurge = current.any { c ->
            val r = c.retentionMs
            r != null && c.messages.any { it.createdAt + r <= now }
        }
        if (!needsPurge) return
        mutate { list ->
            list.map { c ->
                val r = c.retentionMs ?: return@map c
                c.copy(messages = c.messages.filter { it.createdAt + r > now })
            }
        }
    }

    private fun mutate(transform: (List<Conversation>) -> List<Conversation>) {
        if (key == null) return
        _conversations.update(transform)
        persist()
    }

    private fun persist() {
        if (_loadError.value) return
        val k = key?.copyOf() ?: return
        val snapshot = _conversations.value
        scope.launch(io) {
            try {
                val bytes = AesGcm.encrypt(k, StoreCodec.encode(snapshot), AAD)
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } finally {
                k.fill(0)
            }
        }
    }

    private companion object {
        val AAD = "dugy-store-v1".toByteArray()
    }
}
