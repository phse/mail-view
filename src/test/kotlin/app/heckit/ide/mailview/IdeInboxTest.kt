package app.heckit.ide.mailview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class IdeInboxTest {
    /** Ein SMTP-Gespräch wie von einer Anwendung: Anmeldung, zwei Empfänger, Punkt am Zeilenanfang. */
    @Test
    fun smtpConversation() {
        val received = CopyOnWriteArrayList<Pair<SmtpEnvelope, String>>()
        val server = SmtpServer { envelope, data -> received += envelope to String(data); "id1" }
        server.start(0, network = false)
        try {
            Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = socket.getOutputStream()
                fun send(line: String) { output.write("$line\r\n".toByteArray()); output.flush() }
                fun reply(): String {
                    var line = input.readLine()
                    while (line[3] == '-') line = input.readLine()
                    return line
                }
                assertTrue(reply().startsWith("220"))
                send("EHLO app.test"); assertTrue(reply().startsWith("250"))
                send("STARTTLS"); assertTrue(reply().startsWith("454"))
                send("AUTH LOGIN"); assertTrue(reply().startsWith("334"))
                send("dXNlcg=="); assertTrue(reply().startsWith("334"))
                send("cGFzcw=="); assertTrue(reply().startsWith("235"))
                send("RCPT TO:<max@example.org>"); assertTrue(reply().startsWith("503"))
                send("MAIL FROM:<shop@example.com> SIZE=100 BODY=8BITMIME"); assertTrue(reply().startsWith("250"))
                send("RCPT TO:<max@example.org>"); assertTrue(reply().startsWith("250"))
                send("RCPT TO:<admin@example.com>"); assertTrue(reply().startsWith("250"))
                send("DATA"); assertTrue(reply().startsWith("354"))
                send("Subject: Test\r\nTo: max@example.org\r\n\r\n..leading dot\r\nend\r\n.")
                assertEquals("250 2.0.0 Ok: queued as id1", reply())
                send("QUIT"); assertTrue(reply().startsWith("221"))
            }
        } finally {
            server.stop()
        }
        val (envelope, data) = received.single()
        assertEquals("app.test", envelope.helo)
        assertEquals("shop@example.com", envelope.from)
        assertEquals(listOf("max@example.org", "admin@example.com"), envelope.recipients)
        assertEquals("Subject: Test\r\nTo: max@example.org\r\n\r\n.leading dot\r\nend\r\n", data)
    }

    @Test
    fun portInUse() {
        val first = SmtpServer { _, _ -> "" }
        first.start(0, network = false)
        try {
            val second = SmtpServer { _, _ -> "" }
            assertTrue(runCatching { second.start(first.port, network = false) }.isFailure)
        } finally {
            first.stop()
        }
    }

    @Test
    fun storeListsSearchesAndTags() {
        val dir = Files.createTempDirectory("mail-view-inbox")
        val store = InboxStore(dir)
        val envelope = SmtpEnvelope("app.test", "shop@example.com", listOf("max@example.org", "admin@example.com"), "127.0.0.1")
        val first = store.deliver(envelope, "From: Shop <shop@example.com>\r\nTo: max@example.org\r\nSubject: Order 1\r\nX-Tags: order, invoice\r\n\r\nThanks for your order.\r\n".toByteArray())
        Thread.sleep(2)
        val second = store.deliver(envelope.copy(recipients = listOf("eva@example.org")), "From: shop@example.com\r\nTo: eva@example.org\r\nSubject: Newsletter\r\n\r\nNews\r\n".toByteArray())

        val all = store.list(100, null)
        assertEquals(listOf(second, first), all.map { it.id })
        assertEquals("Shop", all[1].from)
        assertEquals(listOf("invoice", "order"), all[1].tags)
        assertFalse(all[1].read)

        val raw = String(store.raw(first))
        assertTrue(raw.startsWith("Return-Path: <shop@example.com>\r\nReceived: from app.test (127.0.0.1)"))
        // Nur im Umschlag genannte Empfänger erscheinen als Bcc.
        assertTrue(raw.contains("Bcc: admin@example.com\r\n"))
        assertTrue(String(store.raw(second)).contains("for <eva@example.org>;"))
        assertFalse(String(store.raw(second)).contains("Bcc:"))

        assertEquals(listOf(first), store.list(100, "thanks ORDER").map { it.id })
        assertEquals(listOf(first), store.list(100, "tag:invoice").map { it.id })
        assertEquals(listOf("invoice", "order"), store.tags())

        store.markRead(listOf(first), true)
        assertTrue(store.list(100, null).first { it.id == first }.read)
        assertTrue(InboxStore(dir).list(100, null).first { it.id == first }.read)

        store.delete(listOf(first))
        assertEquals(listOf(second), store.list(100, null).map { it.id })
        assertTrue(runCatching { store.raw("../etc/passwd") }.isFailure)
        store.deleteAll()
        assertTrue(store.list(100, null).isEmpty())
    }
}
