package app.heckit.ide.mailview

import org.junit.Test
import java.io.File

/** Liest alle .eml und .msg unter -Dmailview.samples=<ordner> (falls gesetzt) und gibt die erkannten Teile oder den Fehler aus. */
class MailParserTest {
    @Test
    fun parsesSamples() {
        val dir = System.getProperty("mailview.samples")?.let(::File) ?: return
        dir.walk().filter { it.extension == "eml" || it.extension == "msg" }.forEach { file ->
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
}
