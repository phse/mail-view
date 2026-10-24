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
}
