package app.dugy.data

import org.json.JSONArray
import org.json.JSONObject

/** تحويل المحادثات إلى JSON والعكس. يُشفَّر الناتج كاملًا قبل الكتابة على القرص. */
object StoreCodec {
    private const val VERSION = 1

    fun encode(conversations: List<Conversation>): ByteArray {
        val array = JSONArray()
        for (c in conversations) {
            val messages = JSONArray()
            for (m in c.messages) {
                val mo = JSONObject()
                mo.put("id", m.id)
                mo.put("out", m.outgoing)
                mo.put("text", m.text)
                mo.put("at", m.createdAt)
                mo.put("status", m.status.name)
                messages.put(mo)
            }
            val contact = JSONObject()
            contact.put("id", c.contact.id)
            contact.put("name", c.contact.name)
            contact.put("handle", c.contact.handle)

            val co = JSONObject()
            co.put("id", c.id)
            co.put("createdAt", c.createdAt)
            co.put("retentionMs", c.retentionMs ?: 0L)
            co.put("contact", contact)
            co.put("messages", messages)
            array.put(co)
        }
        val root = JSONObject()
        root.put("v", VERSION)
        root.put("conversations", array)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<Conversation> {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val array = root.getJSONArray("conversations")
        val result = ArrayList<Conversation>(array.length())
        for (i in 0 until array.length()) {
            val co = array.getJSONObject(i)
            val contact = co.getJSONObject("contact")
            val msgs = co.getJSONArray("messages")
            val messages = ArrayList<Message>(msgs.length())
            for (j in 0 until msgs.length()) {
                val mo = msgs.getJSONObject(j)
                messages += Message(
                    id = mo.getString("id"),
                    outgoing = mo.getBoolean("out"),
                    text = mo.getString("text"),
                    createdAt = mo.getLong("at"),
                    status = MessageStatus.entries.firstOrNull { it.name == mo.optString("status") }
                        ?: MessageStatus.FAILED,
                )
            }
            val retention = co.optLong("retentionMs", 0L)
            result += Conversation(
                id = co.getString("id"),
                contact = Contact(
                    id = contact.getString("id"),
                    name = contact.getString("name"),
                    handle = contact.optString("handle", ""),
                ),
                createdAt = co.getLong("createdAt"),
                retentionMs = if (retention > 0) retention else null,
                messages = messages,
            )
        }
        return result
    }
}
