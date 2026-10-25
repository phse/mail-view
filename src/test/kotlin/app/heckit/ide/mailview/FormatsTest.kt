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

    @Test
    fun uniqueNames() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        assertEquals("a.pdf", MailExport.unique(dir, "a.pdf").also { it.writeText("") }.name)
        assertEquals("a (2).pdf", MailExport.unique(dir, "a.pdf").also { it.writeText("") }.name)
        assertEquals("a (3).pdf", MailExport.unique(dir, "a.pdf").name)
        assertEquals("Re  Frage.eml", MailExport.emlName("Re: Frage?"))
        assertEquals("Bericht 2026.pdf", MailExport.safeName("Bericht 2026.pdf"))
    }

    @Test
    fun msgAsEml() {
        val mail = MailParser.parse(eml.toByteArray())
        val copy = MailParser.parse(EmlWriter.write(mail))
        assertEquals(mail.subject, copy.subject)
        assertEquals(mail.text?.trim(), copy.text?.trim())
    }

    @Test
    fun icsAndVcf() {
        val ics = "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\nSUMMARY:Board meeting\\, July\r\n" +
            "DTSTART;TZID=Europe/Berlin:20260713T180000\r\nDTEND;TZID=Europe/Berlin:20260713T193000\r\n" +
            "LOCATION:Old Mill\r\n  workshop\r\nORGANIZER;CN=\"Lee: Jordan\":mailto:jordan@example.com\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val event = AttachmentPreview.event(ics)!!.text
        assertTrue(event, event.startsWith("Board meeting, July · "))
        assertTrue(event, event.contains("Old Mill workshop"))
        assertTrue(event, event.endsWith("Lee: Jordan"))
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nN:Lee;Jordan;;;\nORG:Riverside Makers;\nitem1.EMAIL;type=INTERNET:jordan@example.com\nTEL;TYPE=CELL:+49 170 0000000\nEND:VCARD\n"
        assertEquals("Jordan Lee · Riverside Makers · jordan@example.com · +49 170 0000000", AttachmentPreview.contact(vcf)!!.text)
    }

    @Test
    fun vcardFromAddresses() {
        val card = Contacts.vcard(listOf(MailAddress("Lee, Jordan", "jordan@example.com"), MailAddress(null, "bob@example.com")))
        assertTrue(card, card.contains("N:Lee;Jordan;;;\r\nFN:Jordan Lee\r\nEMAIL;TYPE=INTERNET:jordan@example.com"))
        assertTrue(card, card.contains("N:;bob;;;\r\nFN:bob\r\n"))
        assertEquals("Jordan Lee · jordan@example.com", AttachmentPreview.contact(card)!!.text)
    }

    @Test
    fun replyLinks() {
        val mail = ParsedMail("Re: Frage", listOf(MailAddress("Anna", "anna@example.org")), listOf(MailAddress(null, "me@example.com")),
            listOf(MailAddress(null, "cc@example.com")), emptyList(), emptyList(), null, null, "Hallo\nWelt", emptyMap(), emptyList())
        val reply = Compose.link(mail, "reply", "", "Re:", "Fwd:", "{sender}:")!!
        assertTrue(reply, reply.startsWith("mailto:anna@example.org?subject=Re%3A%20Frage&body="))
        assertTrue(reply, reply.contains("%3E%20Hallo%0A%3E%20Welt"))
        val all = Compose.link(mail, "replyAll", "", "Re:", "Fwd:", "{sender}:")!!
        assertTrue(all, all.startsWith("mailto:anna@example.org?cc=me%40example.com%2Ccc%40example.com&subject=Re%3A%20Frage"))
        assertTrue(Compose.link(mail, "forward", "", "Re:", "Fwd:", "{sender}:")!!.startsWith("mailto:?subject=Fwd%3A%20Re%3A%20Frage"))
    }

    @Test
    fun quoteHeaderTemplate() {
        val date = java.util.GregorianCalendar(2026, 9, 25, 10, 35).time
        val mail = ParsedMail("x", listOf(MailAddress("JetBrains (JetBrains Marketplace)", "marketplace@jetbrains.com")), emptyList(), emptyList(),
            emptyList(), emptyList(), date, null, "Hi", emptyMap(), emptyList())
        val line = Compose.quoteHeader(mail, "Am {date} um {time} schrieb {sender}:")
        assertTrue(line, line.endsWith("schrieb JetBrains (JetBrains Marketplace) <marketplace@jetbrains.com>:"))
        assertTrue(line, line.startsWith("Am ") && line.contains("2026") && line.contains(" um "))
    }

    @Test
    fun spamHeaders() {
        fun of(vararg headers: Pair<String, String>) = HeaderAnalysis.spam { name -> headers.filter { it.first.equals(name, true) }.map { it.second } }
        val sa = of("X-Spam-Status" to "No, score=-0.1 required=5.0 tests=DKIM_SIGNED")!!
        assertEquals("spam no -0.1 / 5.0", "${sa.method} ${sa.result} ${sa.detail}")
        val rspamd = of("X-Spamd-Result" to "default: True [16.20 / 15.00]; R_SPF_FAIL(1.00)")!!
        assertEquals("yes 16.20 / 15.00", "${rspamd.result} ${rspamd.detail}")
        assertEquals("yes", of("X-Spam-Flag" to "YES")!!.result)
        assertEquals(null, of())
    }

    @Test
    fun unsubscribeHeader() {
        assertEquals(listOf("mailto:leave@example.com?subject=unsubscribe", "https://example.com/u/1"),
            HeaderAnalysis.parseUnsubscribe("<mailto:leave@example.com?subject=unsubscribe>,\n <https://example.com/u/1>, <ftp://x>"))
    }
}
