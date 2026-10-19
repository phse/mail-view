package app.heckit.ide.mailview

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Liest alle .eml unter -Dmailview.samples=<ordner> (falls gesetzt) und gibt die erkannten Teile aus. */
class MailParserTest {
    @Test
    fun parsesSamples() {
        val dir = System.getProperty("mailview.samples")?.let(::File) ?: return
        dir.walk().filter { it.extension == "eml" }.forEach { file ->
            val mail = MailParser.parse(file.readBytes())
            println("${file.name}: '${mail.subject}' from=${mail.from.map { "${it.name} <${it.address}>" }} " +
                "to=${mail.to.size} cc=${mail.cc.size} date=${mail.date} html=${mail.html?.length} text=${mail.text?.length} " +
                "inline=${mail.inlineParts.keys} attachments=${mail.attachments.map { "${it.name} ${it.mimeType} ${it.data.size}" }}")
            assertTrue(mail.html != null || mail.text != null)
        }
    }
}
