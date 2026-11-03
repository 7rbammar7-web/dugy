package app.dugy.e2e

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * اختبارات التشفير الطرفي بمكتبة libsignal الحقيقية (لا محاكاة للتشفير).
 *
 * «الخادم» هنا [FakeServer] يسجّل كل بايت يصل إليه، كما سيصل إلى Firebase تمامًا:
 * المفاتيح العامة المنشورة، ثم الرسائل المشفّرة. نتحقق أنه لا يرى النص ولا المفاتيح الخاصة.
 */
class E2eEngineTest {

    /** خادم وهمي يحتفظ بنسخة من كل ما يُرسل إليه. */
    private class FakeServer {
        val published = HashMap<String, PublishedKeys>()
        val oneTime = HashMap<String, ArrayDeque<PublicPreKey>>()
        val everythingReceived = ArrayList<ByteArray>()
        val inbox = HashMap<String, ArrayDeque<Pair<String, Envelope>>>()

        fun publish(name: String, keys: PublishedKeys) {
            published[name] = keys
            oneTime[name] = ArrayDeque(keys.oneTimePreKeys)
            everythingReceived += listOf(
                keys.identityKey, keys.signedPreKey, keys.signedPreKeySignature,
                keys.kyberPreKey, keys.kyberPreKeySignature,
            )
            keys.oneTimePreKeys.forEach { everythingReceived += it.publicKey }
        }

        fun bundle(name: String): RemoteBundle? {
            val k = published[name] ?: return null
            return RemoteBundle(
                k.registrationId, k.identityKey, k.signedPreKeyId, k.signedPreKey, k.signedPreKeySignature,
                k.kyberPreKeyId, k.kyberPreKey, k.kyberPreKeySignature, oneTime[name]?.removeFirstOrNull(),
            )
        }

        fun send(from: String, to: String, envelope: Envelope) {
            everythingReceived += envelope.body
            inbox.getOrPut(to) { ArrayDeque() }.addLast(from to envelope)
        }

        fun take(to: String): Pair<String, Envelope> = inbox.getValue(to).removeFirst()
    }

    private class User(val name: String, var store: DugySignalStore = DugySignalStore.create())

    private fun register(server: FakeServer, user: User) {
        server.publish(user.name, E2eEngine.generateKeys(user.store, oneTimeCount = 5))
    }

    private fun send(server: FakeServer, from: User, to: User, text: String) {
        val result = E2eEngine.encrypt(
            from.store, from.name, to.name, text,
            publishedIdentity = server.published[to.name]?.identityKey,
        ) { server.bundle(to.name) }
        assertTrue("expected Ok but was $result", result is EncryptResult.Ok)
        server.send(from.name, to.name, (result as EncryptResult.Ok).envelope)
    }

    private fun receive(server: FakeServer, user: User): String {
        val (from, envelope) = server.take(user.name)
        return E2eEngine.decrypt(user.store, user.name, from, envelope)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun recipientDecryptsAndServerNeverSeesPlaintext() {
        val server = FakeServer()
        val alice = User("alice")
        val bob = User("bob")
        register(server, alice)
        register(server, bob)

        val secret = "اجتماعنا السري الساعة التاسعة — meet at nine"
        send(server, alice, bob, secret)
        assertEquals(secret, receive(server, bob))

        val reply = "تمام، سأكون هناك"
        send(server, bob, alice, reply)
        assertEquals(reply, receive(server, alice))

        // الخادم لم يستقبل النص الصريح بأي شكل.
        for (text in listOf(secret, reply)) {
            val utf8 = text.toByteArray(Charsets.UTF_8)
            val padded = E2eEngine.pad(utf8)
            for (blob in server.everythingReceived) {
                assertFalse("server saw plaintext", blob.contains(utf8))
                assertFalse("server saw padded plaintext", blob.contains(padded.copyOf(32)))
            }
        }
    }

    @Test
    fun serverNeverReceivesPrivateKeys() {
        val server = FakeServer()
        val alice = User("alice")
        register(server, alice)
        val identityPrivate = alice.store.getIdentityKeyPair().privateKey.serialize()
        val signedPrivate = alice.store.loadSignedPreKeys().first().keyPair.privateKey.serialize()
        val kyberSecret = alice.store.loadKyberPreKeys().first().keyPair.secretKey.serialize()
        for (blob in server.everythingReceived) {
            assertFalse(blob.contains(identityPrivate))
            assertFalse(blob.contains(signedPrivate))
            assertFalse(blob.contains(kyberSecret.copyOf(64)))
        }
    }

    @Test
    fun thirdPartyCannotDecrypt() {
        val server = FakeServer()
        val alice = User("alice")
        val bob = User("bob")
        val eve = User("bob") // يحاول انتحال اسم bob بمفاتيح أخرى
        register(server, alice)
        register(server, bob)

        send(server, alice, bob, "لبوب فقط")
        val (_, envelope) = server.take("bob")
        try {
            E2eEngine.decrypt(eve.store, "bob", "alice", envelope)
            fail("a third party decrypted the message")
        } catch (expected: Exception) {
            // متوقع: لا يملك مفاتيح بوب الخاصة.
        }
        // والمستلم الحقيقي ما زال يستطيع فكها.
        assertEquals("لبوب فقط", E2eEngine.decrypt(bob.store, "bob", "alice", envelope))
    }

    @Test
    fun tamperedMessageIsRejected() {
        val server = FakeServer()
        val alice = User("alice")
        val bob = User("bob")
        register(server, alice)
        register(server, bob)
        send(server, alice, bob, "رسالة")
        val (from, envelope) = server.take("bob")
        val body = envelope.body.copyOf()
        body[body.size - 5] = (body[body.size - 5].toInt() xor 1).toByte()
        try {
            E2eEngine.decrypt(bob.store, "bob", from, Envelope(envelope.type, body))
            fail("tampered message accepted")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun storeSurvivesSerialization() {
        val server = FakeServer()
        val alice = User("alice")
        val bob = User("bob")
        register(server, alice)
        register(server, bob)
        send(server, alice, bob, "أولى")
        assertEquals("أولى", receive(server, bob))

        alice.store = DugySignalStore.deserialize(alice.store.serialize())
        bob.store = DugySignalStore.deserialize(bob.store.serialize())

        send(server, alice, bob, "بعد إعادة التشغيل")
        assertEquals("بعد إعادة التشغيل", receive(server, bob))
        send(server, bob, alice, "وصلت")
        assertEquals("وصلت", receive(server, alice))
    }

    @Test
    fun safetyNumbersMatchOnBothSidesAndDifferForImpostor() {
        val alice = DugySignalStore.create()
        val bob = DugySignalStore.create()
        val impostor = DugySignalStore.create()
        val onAlice = E2eEngine.safetyNumber("alice", alice.publicIdentity, "bob", bob.publicIdentity)
        val onBob = E2eEngine.safetyNumber("bob", bob.publicIdentity, "alice", alice.publicIdentity)
        assertEquals(60, onAlice.length)
        assertEquals(onAlice, onBob)
        val withImpostor = E2eEngine.safetyNumber("alice", alice.publicIdentity, "bob", impostor.publicIdentity)
        assertNotEquals(onAlice, withImpostor)
    }

    @Test
    fun keyChangeBlocksSendingUntilAccepted() {
        val server = FakeServer()
        val alice = User("alice")
        val bob = User("bob")
        register(server, alice)
        register(server, bob)
        send(server, alice, bob, "قبل")
        assertEquals("قبل", receive(server, bob))
        alice.store.setVerified("bob", true)
        assertTrue(alice.store.isVerified("bob"))

        // بوب يعيد تثبيت التطبيق: مفاتيح جديدة كليًا.
        bob.store = DugySignalStore.create()
        register(server, bob)

        val blocked = E2eEngine.encrypt(
            alice.store, "alice", "bob", "هل هذا أنت؟",
            publishedIdentity = server.published["bob"]!!.identityKey,
        ) { server.bundle("bob") }
        assertTrue(blocked is EncryptResult.KeyChanged)
        assertTrue(alice.store.hasKeyChangeWarning("bob"))
        assertFalse("verification must be cleared", alice.store.isVerified("bob"))
        assertTrue(server.inbox["bob"].isNullOrEmpty())

        alice.store.acceptPendingIdentity("bob")
        assertArrayEquals(server.published["bob"]!!.identityKey, alice.store.identityOf("bob")!!.serialize())
        send(server, alice, bob, "بعد القبول")
        assertEquals("بعد القبول", receive(server, bob))
    }

    @Test
    fun incomingMessageFromChangedKeyIsReadButFlagged() {
        val server = FakeServer()
        val alice = User("alice")
        val bob = User("bob")
        register(server, alice)
        register(server, bob)
        send(server, bob, alice, "مرحبا")
        assertEquals("مرحبا", receive(server, alice))

        bob.store = DugySignalStore.create()
        register(server, bob)
        send(server, bob, alice, "رقمي الجديد")
        assertEquals("رقمي الجديد", receive(server, alice))
        assertTrue(alice.store.hasKeyChangeWarning("bob"))
        alice.store.dismissKeyChangeNotice("bob")
        assertFalse(alice.store.hasKeyChangeWarning("bob"))
    }

    @Test
    fun replayedMessageIsRejected() {
        val server = FakeServer()
        val alice = User("alice")
        val bob = User("bob")
        register(server, alice)
        register(server, bob)
        send(server, alice, bob, "مرة واحدة")
        val (from, envelope) = server.take("bob")
        assertEquals("مرة واحدة", E2eEngine.decrypt(bob.store, "bob", from, envelope))
        try {
            E2eEngine.decrypt(bob.store, "bob", from, envelope)
            fail("replayed message accepted")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun paddingHidesExactLength() {
        val a = E2eEngine.pad("hi".toByteArray())
        val b = E2eEngine.pad("hello there".toByteArray())
        assertEquals(a.size, b.size)
        assertArrayEquals("hi".toByteArray(), E2eEngine.unpad(a))
    }
}
