package app.heckit.ide.mailview

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Liest alle .eml und .msg unter -Dmailview.samples=<ordner> (falls gesetzt) und gibt die erkannten Teile oder den Fehler aus. */
class MailParserTest {
    @Test
    fun parsesSamples() {
        val dir = System.getProperty("mailview.samples")?.let(::File) ?: return
        dir.walk().filter { it.extension in setOf("eml", "emlx", "mht", "msg", "oft", "dat", "tnef") }.forEach { file ->
            val mail = try {
                MailParser.parse(file.readBytes())
            } catch (e: Exception) {
                println("${file.name}: ERROR $e")
                return@forEach
            }
            println("${file.name}: '${mail.subject}' from=${mail.from.map { "${it.name} <${it.address}>" }} " +
                "to=${mail.to.size} cc=${mail.cc.size} date=${mail.date} html=${mail.html?.length} text=${mail.text?.length} " +
                "headers=${mail.headers?.lines()?.size} inline=${mail.inlineParts.keys} attachments=${mail.attachments.map { "${it.name} ${it.mimeType} ${it.data.size}" }}")
        }
    }

    /** Unkodierte Umlaute im Header: als UTF-8 lesbar, sonst Latin-1 (alte Mailer). */
    @Test
    fun rawHeaderCharsets() {
        val utf8 = "Subject: Grüße\r\nFrom: Jörg <j@example.com>\r\n\r\nx".toByteArray(Charsets.UTF_8)
        val mail = MailParser.parse(utf8)
        assertEquals("Grüße", mail.subject)
        assertEquals("Jörg", mail.from.single().name)
        assertEquals(true, mail.headers?.contains("Subject: Grüße"))
        val latin1 = MailParser.parse("Subject: Grüße\r\n\r\nx".toByteArray(Charsets.ISO_8859_1))
        assertEquals("Grüße", latin1.subject)
        assertEquals("=?UTF-8?Q?x?= bleibt", MailParser.rawUtf8("=?UTF-8?Q?x?= bleibt"))
    }
}
