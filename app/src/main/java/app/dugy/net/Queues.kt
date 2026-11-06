package app.dugy.net

import android.content.Context
import app.dugy.e2e.Envelope
import app.dugy.security.KeystoreCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * طابوران صغيران على الهاتف، لأن الخادم لا يحفظ أي رسالة:
 *  - الصادر: رسائل مشفّرة جاهزة تنتظر أن يتصل المستلم.
 *  - الوارد: رسائل مشفّرة وصلت والتطبيق مقفل، تُفك عند فتحه.
 *
 * محتواهما مشفّر طرفيًا أصلًا (لا يفكّه إلا هاتف المستلم)، ويُغلَّفان فوق ذلك بمفتاح
 * مخزن مفاتيح أندرويد ليمكن استخدامهما والتطبيق مقفل. كل الدوال تُستدعى من خيط واحد.
 */
class OutItem(
    val id: String,
    val conversationId: String,
    val toUid: String,
    val sentAt: Long,
    val envelope: Envelope,
    val state: Int = PENDING,
) {
    fun with(newState: Int) = OutItem(id, conversationId, toUid, sentAt, envelope, newState)

    companion object {
        const val PENDING = 0
        const val DELIVERED = 1
        const val FAILED = 2
    }
}

class Outbox(context: Context) : SealedFile<OutItem>(context, "outbox.bin") {
    override fun write(out: DataOutputStream, item: OutItem) {
        out.writeUTF(item.id)
        out.writeUTF(item.conversationId)
        out.writeUTF(item.toUid)
        out.writeLong(item.sentAt)
        out.writeInt(item.state)
        writeEnvelope(out, item.envelope)
    }

    override fun read(input: DataInputStream) = OutItem(
        id = input.readUTF(),
        conversationId = input.readUTF(),
        toUid = input.readUTF(),
        sentAt = input.readLong(),
        state = input.readInt(),
        envelope = readEnvelope(input),
    )

    fun add(item: OutItem) = update { list -> list.filterNot { it.id == item.id } + item }
    fun setState(id: String, state: Int) = update { list -> list.map { if (it.id == id) it.with(state) else it } }
    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }
    fun pending(): List<OutItem> = items().filter { it.state == OutItem.PENDING }
    fun find(id: String): OutItem? = items().firstOrNull { it.id == id }
}

class Inbox(context: Context) : SealedFile<IncomingEnvelope>(context, "inbox.bin") {
    override fun write(out: DataOutputStream, item: IncomingEnvelope) {
        out.writeUTF(item.id)
        out.writeUTF(item.fromUid)
        out.writeUTF(item.fromName)
        out.writeLong(item.sentAt)
        writeEnvelope(out, item.envelope)
    }

    override fun read(input: DataInputStream) = IncomingEnvelope(
        id = input.readUTF(),
        fromUid = input.readUTF(),
        fromName = input.readUTF(),
        sentAt = input.readLong(),
        envelope = readEnvelope(input),
    )

    /** يعيد false إذا كانت الرسالة محفوظة من قبل (تكرار). */
    fun add(item: IncomingEnvelope): Boolean {
        if (items().any { it.id == item.id && it.fromUid == item.fromUid }) return false
        update { it + item }
        return true
    }

    fun remove(item: IncomingEnvelope) =
        update { list -> list.filterNot { it.id == item.id && it.fromUid == item.fromUid } }
}

abstract class SealedFile<T>(context: Context, name: String) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, name)
    private val tmp = File(appContext.filesDir, "$name.tmp")
    private var cache: List<T>? = null

    protected abstract fun write(out: DataOutputStream, item: T)
    protected abstract fun read(input: DataInputStream): T

    fun items(): List<T> = cache ?: load().also { cache = it }

    protected fun update(transform: (List<T>) -> List<T>) {
        val next = transform(items())
        cache = next
        save(next)
    }

    fun clear() {
        cache = emptyList()
        file.delete()
        tmp.delete()
    }

    private fun load(): List<T> {
        if (!file.exists()) return emptyList()
        return try {
            val plain = KeystoreCipher.decrypt(file.readBytes())
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                List(input.readInt()) { read(input) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<T>) {
        if (list.isEmpty()) {
            file.delete()
            return
        }
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(list.size)
                list.forEach { write(out, it) }
            }
        }.toByteArray()
        tmp.writeBytes(KeystoreCipher.encrypt(appContext, plain))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    protected fun writeEnvelope(out: DataOutputStream, e: Envelope) {
        out.writeInt(e.type)
        out.writeInt(e.body.size)
        out.write(e.body)
    }

    protected fun readEnvelope(input: DataInputStream): Envelope {
        val type = input.readInt()
        val size = input.readInt()
        require(size in 0..(1 shl 20))
        return Envelope(type, ByteArray(size).also { input.readFully(it) })
    }
}
