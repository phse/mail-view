package app.heckit.ide.mailview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailLogTest {
    private val dashes = "-".repeat(79)

    /** Laravel (`MAIL_MAILER=log`): quoted-printable schon dekodiert, der Header sagt es noch. */
    private val laravel = """
        [2026-11-20 09:14:03] local.INFO: User registered {"id":5}
        [2026-11-20 09:14:03] local.DEBUG: From: Demo Shop <hello@example.com>
        To: max@example.org
        Subject: Reset your password
        Message-ID: <abc@example.com>
        MIME-Version: 1.0
        Date: Thu, 20 Nov 2026 09:14:03 +0000
        Content-Type: multipart/alternative; boundary=4dfWQk9N

        --4dfWQk9N
        Content-Type: text/plain; charset=utf-8
        Content-Transfer-Encoding: quoted-printable

        Hallo Max, hier geht es weiter: https://example.com/reset?token=abc
        --4dfWQk9N
        Content-Type: text/html; charset=utf-8
        Content-Transfer-Encoding: quoted-printable

        <p style="color:#333">Hallo Max, viele Grüße</p>
        --4dfWQk9N--
        [2026-11-20 09:14:04] local.INFO: Done
        [2026-11-20 09:15:00] local.DEBUG: From: Demo Shop <hello@example.com>
        To: eva@example.org
        Subject: Welcome
        Message-ID: <def@example.com>
        MIME-Version: 1.0
        Date: Thu, 20 Nov 2026 09:15:00 +0000
        Content-Type: text/plain; charset=utf-8
        Content-Transfer-Encoding: quoted-printable

        Welcome, Eva!
        [2026-11-20 09:15:01] local.INFO: Done
    """.trimIndent()

    private fun django(subject: String, body: String) = """
        Content-Type: text/plain; charset="utf-8"
        MIME-Version: 1.0
        Content-Transfer-Encoding: 7bit
        Subject: $subject
        From: webmaster@localhost
        To: max@example.org
        Date: Thu, 20 Nov 2026 09:14:03 -0000
        Message-ID: <176.$subject@host>

        BODY
    """.trimIndent().replace("BODY", body)

    private fun rails(subject: String, body: String) = """
        Date: Thu, 20 Nov 2026 10:00:00 +0100
        From: shop@example.com
        To: max@example.org
        Message-ID: <$subject@example.com>
        Subject: $subject
        Mime-Version: 1.0
        Content-Type: text/plain; charset=UTF-8
        Content-Transfer-Encoding: 7bit

        BODY
    """.trimIndent().replace("BODY", body)

    private fun scan(text: String) = MailLog.scan(text.toByteArray().inputStream())
    private fun read(text: String, entry: MboxEntry) = MailLog.read(text.toByteArray(), entry)

    @Test
    fun laravelLog() {
        val entries = scan(laravel)
        assertEquals(listOf("Reset your password", "Welcome"), entries.map { it.subject })
        assertTrue(entries.all { it.decodedQp })
        val mail = MailParser.parse(read(laravel, entries[0]))
        assertEquals("Reset your password", mail.subject)
        assertTrue(mail.html!!.contains("style=\"color:#333\""))
        assertTrue(mail.html!!.contains("Grüße"))
        assertTrue(mail.text!!.contains("token=abc"))
        // Endet am schließenden Trenner, die nächste Logzeile gehört nicht dazu.
        assertTrue(String(read(laravel, entries[0])).endsWith("--4dfWQk9N--"))
        assertEquals("Welcome, Eva!", MailParser.parse(read(laravel, entries[1])).text?.trim())
    }

    @Test
    fun djangoFileAndConsole() {
        val file = django("First", "Hello,\nreset here.") + "\n$dashes\n" + django("Second", "Bye") + "\n$dashes\n"
        assertEquals(listOf("First", "Second"), scan(file).map { it.subject })
        // Konsole von runserver: Mails zwischen Zugriffszeilen.
        val console = "[20/Nov/2026 09:14:00] \"GET /reset/ HTTP/1.1\" 200 1234\n" + django("Reset", "Text") +
            "\n$dashes\n[20/Nov/2026 09:14:03] \"POST /reset/ HTTP/1.1\" 302 0\n"
        val entries = scan(console)
        assertEquals(listOf("Reset"), entries.map { it.subject })
        assertEquals("Text", MailParser.parse(read(console, entries[0])).text?.trim())
    }

    @Test
    fun railsFileDelivery() {
        val forwarded = "Welcome!\n\n---------- Forwarded message ---------\nFrom: Anna <anna@example.org>\nDate: Wed, 19 Nov 2026\n" +
            "Subject: Old\nTo: max@example.org\n\nQuoted text"
        val file = rails("Welcome", forwarded) + "\r\n\r\n" + rails("Second", "Second body") + "\r\n\r\n"
        val entries = scan(file)
        assertEquals(listOf("Welcome", "Second"), entries.map { it.subject })
        assertTrue(MailParser.parse(read(file, entries[0])).text!!.contains("Quoted text"))
    }

    @Test
    fun railsDevelopmentLog() {
        val log = "Started POST \"/users\" for ::1\nDelivered mail 1@example.com (12.3ms)\n" + rails("Welcome", "Hi Max") +
            "\nCompleted 302 Found in 50ms\n"
        val entries = scan(log)
        assertEquals(listOf("Welcome"), entries.map { it.subject })
        assertEquals("Hi Max", MailParser.parse(read(log, entries[0])).text?.trim())
    }

    /** Demo-Log für die Screenshots. */
    @Test
    fun demoLog() {
        val file = java.io.File("marketplace/demo-project/laravel.log")
        val entries = MailLog.scan(file.toPath())
        assertEquals(listOf("Welcome to Riverside Makers", "Order #1042 received", "Reset your password"), entries.map { it.subject })
        val mail = MailParser.parse(MailLog.read(file.toPath(), entries[1]))
        assertTrue(mail.html!!.contains("74,90 €"))
    }

    @Test
    fun ignoresOrdinaryLogs() {
        assertTrue(scan("INFO: started\nWARNING: Subject: none\nFrom: x\nTo: y\n\nbody").isEmpty())
        assertTrue(MailLog.scan("plain text").isEmpty())
    }

    @Test
    fun textScanFindsMailsInDocument() {
        val entries = MailLog.scan(laravel)
        assertEquals(2, entries.size)
        assertTrue(entries[0].start > 0)
    }

    @Test
    fun consoleFilterLinksSubject() {
        val filter = MailConsoleFilter()
        var length = 0
        val results = (django("Reset", "Text") + "\n$dashes\nnext line\n").lines().map { line ->
            val withBreak = "$line\n"
            length += withBreak.length
            line to filter.applyFilter(withBreak, length)
        }
        val linked = results.filter { it.second != null }
        assertEquals(listOf("Subject: Reset"), linked.map { it.first })
        val result = linked.single().second!!
        val subjectLine = results.indexOfFirst { it.first == "Subject: Reset" }
        val lineStart = results.take(subjectLine).sumOf { it.first.length + 1 }
        assertEquals(lineStart + "Subject: ".length, result.resultItems.single().highlightStartOffset)
        assertNotNull(result.resultItems.single().hyperlinkInfo)
    }

    @Test
    fun consoleFilterIgnoresForwardedText() {
        val filter = MailConsoleFilter()
        val text = "From: Anna <anna@example.org>\nDate: Wed, 19 Nov 2026\nSubject: Old\nTo: max@example.org\n"
        var length = 0
        val results = text.lines().map { val l = "$it\n"; length += l.length; filter.applyFilter(l, length) }
        assertTrue(results.all { it == null })
        assertNull(MailLog.boundary("Content-Type: text/plain\n"))
        assertEquals("b1", MailLog.boundary("Content-Type: multipart/mixed;\n boundary=\"b1\"\n"))
    }
}
