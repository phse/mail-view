package app.heckit.ide.mailview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailToolsTest {
    private fun mail(vararg lines: String) = lines.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)

    private val multipart = mail(
        "From: =?UTF-8?Q?J=C3=B6rg?= <j@example.com>",
        "To: a@example.com",
        "Subject: =?UTF-8?B?R3LDvMOfZQ==?=",
        "Date: Thu, 5 Nov 2026 10:00:00 +0100",
        "Message-ID: <1@example.com>",
        "MIME-Version: 1.0",
        "Content-Type: multipart/mixed; boundary=\"b1\"",
        "",
        "--b1",
        "Content-Type: text/plain; charset=utf-8",
        "Content-Transfer-Encoding: quoted-printable",
        "",
        "Gr=C3=BC=C3=9Fe",
        "zweite Zeile",
        "--b1",
        "Content-Type: application/pdf; name=\"a.pdf\"",
        "Content-Transfer-Encoding: base64",
        "",
        "JVBERi0xLjQK",
        "AAAA",
        "--b1--",
        "",
    )

    @Test
    fun cleanMailHasNoWarnings() {
        assertEquals(emptyList<String>(), MailParser.parse(multipart).warnings.map { it.toString() })
    }

    @Test
    fun reportsMissingHeadersAndOpenBoundary() {
        val broken = mail(
            "From: a@example.com",
            "Date: gestern",
            "Content-Type: multipart/mixed; boundary=x",
            "",
            "--x",
            "Content-Type: text/plain; charset=us-ascii",
            "",
            "Grüße",
            "",
        )
        val keys = MailParser.parse(broken).warnings.map { it.key }
        assertTrue(keys.toString(), "lint.date" in keys)
        assertTrue(keys.toString(), "lint.missing" in keys) // Message-ID
        assertTrue(keys.toString(), "lint.boundary.open" in keys)
        assertTrue(keys.toString(), "lint.charset.invalid" in keys)
    }

    @Test
    fun detectsUtf8DeclaredAsLatin() {
        val m = mail(
            "From: a@example.com", "Date: Thu, 5 Nov 2026 10:00:00 +0100", "Message-ID: <x@y>",
            "Content-Type: text/plain; charset=us-ascii", "", String("Grüße".toByteArray(), Charsets.ISO_8859_1), "",
        )
        assertEquals(listOf("lint.charset.utf8"), MailParser.parse(m).warnings.map { it.key })
    }

    @Test
    fun normalizedIgnoresEncodingAndHeaderOrder() {
        val other = mail(
            "Message-ID: <1@example.com>",
            "Subject: =?ISO-8859-1?Q?Gr=FC=DFe?=",
            "To: a@example.com",
            "from: =?UTF-8?B?SsO2cmc=?= <j@example.com>",
            "Date: Thu, 5 Nov 2026 10:00:00 +0100",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed;",
            "  boundary=\"zz\"",
            "",
            "--zz",
            "Content-Type: text/plain; charset=utf-8",
            "Content-Transfer-Encoding: base64",
            "",
            "R3LDvMOfZQp6d2VpdGUgWmVpbGU=",
            "--zz",
            "Content-Type: application/pdf; name=\"a.pdf\"",
            "Content-Transfer-Encoding: base64",
            "",
            "JVBERi0xLjQKAAAA",
            "--zz--",
        )
        val a = MailNormalizer.normalize(MailParser.parse(multipart)).lines().filterNot { it.startsWith("Content-Type") }
        val b = MailNormalizer.normalize(MailParser.parse(other)).lines().filterNot { it.startsWith("Content-Type") }
        assertEquals(a.joinToString("\n"), b.joinToString("\n"))
        assertTrue(a.contains("Subject: Grüße"))
    }

    @Test
    fun textStructureFindsNamesPartsAndEncodedValues() {
        val text = String(multipart, Charsets.ISO_8859_1)
        val s = MailTextStructure.parse(text)
        assertTrue(s.headerNames.map { it.substring(text) }.containsAll(listOf("From", "Subject", "Content-Type", "Content-Transfer-Encoding")))
        assertEquals(3, s.boundaries.size)
        assertEquals(listOf("Jörg <j@example.com>", "Grüße"), s.encodedValues.map { it.second })
        assertEquals("=?UTF-8?B?R3LDvMOfZQ==?=", s.encodedValues[1].first.substring(text))
        assertEquals(2, s.parts.size)
        assertFalse(s.parts[0].collapsed)
        assertEquals("Gr=C3=BC=C3=9Fe\r\nzweite Zeile", s.parts[0].body.substring(text))
        assertTrue(s.parts[1].collapsed)
        assertTrue(s.parts[1].description, s.parts[1].description.contains("a.pdf · application/pdf · base64"))
    }
}
