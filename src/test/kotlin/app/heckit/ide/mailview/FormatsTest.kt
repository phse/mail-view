package app.heckit.ide.mailview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {
    private val eml = """
        Received: from mx.example.org (mx.example.org [192.0.2.1]) by mail.example.com with ESMTPS id abc; Mon, 5 Oct 2026 10:00:07 +0200
        Received: from sender.example.org by mx.example.org with ESMTP; Mon, 5 Oct 2026 10:00:00 +0200
        Authentication-Results: mail.example.com; spf=pass smtp.mailfrom=example.org; dkim=pass header.d=example.org header.s=s1; dmarc=fail header.from=example.org
        Subject: =?UTF-8?Q?Gr=C3=BC=C3=9Fe?=
        From: Anna <anna@example.org>
        To: bob@example.com
        Date: Mon, 5 Oct 2026 10:00:00 +0200
        MIME-Version: 1.0
        Content-Type: text/plain; charset=UTF-8

        Hallo
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun emlx() {
        val body = eml.toByteArray()
        val emlx = "${body.size}\n".toByteArray() + body + "<?xml version=\"1.0\"?><plist/>".toByteArray()
        val mail = MailParser.parse(emlx)
        assertEquals("Grüße", mail.subject)
        assertEquals("Hallo", mail.text?.trim())
    }

    @Test
    fun analysis() {
        val analysis = HeaderAnalysis.of(MailParser.parse(eml.toByteArray()))
        assertNotNull(analysis)
        assertEquals(listOf("spf pass example.org", "dkim pass example.org", "dmarc fail example.org"),
            analysis!!.auth.map { "${it.method} ${it.result} ${it.detail}" })
        assertEquals(listOf("sender.example.org", "mx.example.org"), analysis.hops.map { it.from })
        assertEquals("mail.example.com", analysis.hops[1].by)
        assertEquals(7000L, analysis.hops[1].delayMs)
        assertEquals(7000L, analysis.totalMs)
    }

    @Test
    fun mhtLocations() {
        val mht = """
            From: <Saved by Blink>
            Subject: Page
            MIME-Version: 1.0
            Content-Type: multipart/related; type="text/html"; boundary="B"

            --B
            Content-Type: text/html
            Content-Location: https://example.org/page/index.html

            <html><body><img src="img/a.png"><img src="https://example.org/page/img/b.png"></body></html>
            --B
            Content-Type: image/png
            Content-Transfer-Encoding: base64
            Content-Location: https://example.org/page/img/a.png

            iVBORw0K
            --B
            Content-Type: image/png
            Content-Transfer-Encoding: base64
            Content-Location: https://example.org/page/img/b.png

            iVBORw0K
            --B--
        """.trimIndent().replace("\n", "\r\n")
        val mail = MailParser.parse(mht.toByteArray())
        assertEquals(2, mail.locationParts.size)
        assertTrue(mail.attachments.isEmpty())
        assertTrue(MailHtmlRenderer.unreferencedInline(mail).isEmpty())
    }

    @Test
    fun emlWriterRoundTrip() {
        val original = MailParser.parse(eml.toByteArray())
        val withAttachment = ParsedMail(original.subject, original.from, original.to, emptyList(), emptyList(), emptyList(),
            original.date, "<p>Hallo</p>", "Hallo", emptyMap(), listOf(MailAttachment("Bericht ä.pdf", "application/pdf", byteArrayOf(1, 2, 3))),
            original.headers)
        val copy = MailParser.parse(EmlWriter.write(withAttachment))
        assertEquals("Grüße", copy.subject)
        assertEquals("anna@example.org", copy.from.single().address)
        assertEquals("<p>Hallo</p>", copy.html)
        assertEquals("Bericht ä.pdf", copy.attachments.single().name)
        assertEquals(3, copy.attachments.single().data.size)
        assertEquals(2, HeaderAnalysis.of(copy)!!.hops.size)
    }

    /** Kleinste winmail.dat: ein Anhang mit Titel und Daten. */
    private fun tnef(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val b = { size: Int -> java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN) }
        out.write(b(6).putInt(0x223E9F78).putShort(1).array())
        fun attr(level: Int, id: Int, data: ByteArray) {
            out.write(level)
            out.write(b(8).putInt(id).putInt(data.size).array())
            out.write(data)
            out.write(b(2).putShort(0).array())
        }
        attr(1, 0x00018004, "Bericht\u0000".toByteArray())
        attr(2, 0x00069002, ByteArray(14))
        attr(2, 0x00018010, "notes.txt\u0000".toByteArray())
        attr(2, 0x0006800F, "Hallo TNEF".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun winmailDat() {
        val standalone = MailParser.parse(tnef())
        assertEquals("Bericht", standalone.subject)
        assertEquals("notes.txt", standalone.attachments.single().name)

        val base64 = java.util.Base64.getMimeEncoder().encodeToString(tnef())
        val eml = "Subject: Outlook\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"B\"\r\n\r\n" +
            "--B\r\nContent-Type: text/plain\r\n\r\nHallo\r\n" +
            "--B\r\nContent-Type: application/ms-tnef; name=\"winmail.dat\"\r\nContent-Disposition: attachment; filename=\"winmail.dat\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n$base64\r\n--B--\r\n"
        val mail = MailParser.parse(eml.toByteArray())
        assertEquals(listOf("notes.txt"), mail.attachments.map { it.name })
        assertEquals("Hallo TNEF", String(mail.attachments.single().data))
    }

    @Test
    fun mbox() {
        val mbox = """
            From anna@example.org Mon Oct  5 10:00:00 2026
            From: Anna <anna@example.org>
            Subject: Erste
            Date: Mon, 5 Oct 2026 10:00:00 +0200

            Hallo
            >From Berlin mit Gruß

            From bob@example.com Tue Oct  6 11:00:00 2026
            From: bob@example.com
            Subject: =?UTF-8?Q?Zw=C3=B6ite?=

            Text
        """.trimIndent() + "\n"
        val bytes = mbox.toByteArray()
        val entries = MboxIndex.scan(bytes.inputStream())
        assertEquals(listOf("Erste", "Zwöite"), entries.map { it.subject })
        assertEquals(listOf("Anna", "bob@example.com"), entries.map { it.from })
        val first = MailParser.parse(MboxIndex.read(bytes, entries[0]))
        assertEquals("Hallo\nFrom Berlin mit Gruß\n", first.text)
    }
}
