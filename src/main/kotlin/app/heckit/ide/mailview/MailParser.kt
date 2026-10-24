package app.heckit.ide.mailview

import jakarta.mail.Address
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimePartDataSource
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Date
import java.util.Properties

class MailAddress(val name: String?, val address: String?)

class MailAttachment(val name: String, val mimeType: String, val data: ByteArray)

class ParsedMail(
    val subject: String?,
    val from: List<MailAddress>,
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val bcc: List<MailAddress>,
    val replyTo: List<MailAddress>,
    val date: Date?,
    val html: String?,
    val text: String?,
    /** Content-ID (ohne spitze Klammern) → eingebettetes Bild, für cid:-Verweise im HTML. */
    val inlineParts: Map<String, MailAttachment>,
    val attachments: List<MailAttachment>,
    /** Alle Kopfzeilen im Original (gefaltet), bei .msg die Transport-Header; null, wenn keine vorliegen. */
    val headers: String? = null,
    /** Content-Location → Teil, für .mht/.mhtml (Bilder und Styles werden dort über ihre Adresse verknüpft). */
    val locationParts: Map<String, MailAttachment> = emptyMap(),
)

/**
 * Liest eine Mail mit Jakarta Mail. getContent() wird bewusst nicht benutzt: das bräuchte die
 * Mailcap-Registrierung von Jakarta Activation, die im Plugin-Classloader nicht zuverlässig greift.
 */
object MailParser {
    private val session: Session = Session.getInstance(Properties().apply {
        setProperty("mail.mime.address.strict", "false")
        setProperty("mail.mime.decodetext.strict", "false")
        setProperty("mail.mime.decodefilename", "true")
    })

    fun parse(bytes: ByteArray): ParsedMail {
        if (MsgParser.isMsg(bytes)) return MsgParser.parse(bytes)
        val message = MimeMessage(session, ByteArrayInputStream(stripEmlx(bytes)))
        val collector = Collector()
        collector.walk(message)
        return ParsedMail(
            subject = message.getHeader("Subject", null)?.let(::decode),
            from = addresses(message, "From"),
            to = addresses(message, "To"),
            cc = addresses(message, "Cc"),
            bcc = addresses(message, "Bcc"),
            replyTo = addresses(message, "Reply-To"),
            date = runCatching { message.sentDate }.getOrNull(),
            html = collector.html,
            text = collector.text,
            inlineParts = collector.inline,
            attachments = collector.attachments,
            headers = message.allHeaderLines.toList().joinToString("\n").takeIf { it.isNotBlank() },
            locationParts = collector.locations,
        )
    }

    /**
     * Apple Mail (.emlx): erste Zeile ist die Länge der eigentlichen Mail in Bytes, danach folgt eine plist mit
     * Mail-internen Angaben. Am Inhalt erkannt statt an der Endung, damit auch umbenannte Dateien gehen.
     */
    internal fun stripEmlx(bytes: ByteArray): ByteArray {
        val newline = bytes.indexOf('\n'.code.toByte()).takeIf { it in 1..20 } ?: return bytes
        val length = String(bytes, 0, newline, Charsets.US_ASCII).trim().toIntOrNull() ?: return bytes
        val start = newline + 1
        return bytes.copyOfRange(start, minOf(bytes.size, start + length))
    }

    private fun addresses(message: Message, header: String): List<MailAddress> {
        val raw = message.getHeader(header)?.joinToString(", ") ?: return emptyList()
        return try {
            InternetAddress.parseHeader(raw, false).map(::toMailAddress)
        } catch (_: Exception) {
            listOf(MailAddress(decode(raw), null))
        }
    }

    private fun toMailAddress(address: Address): MailAddress =
        if (address is InternetAddress) MailAddress(address.personal?.takeIf { it.isNotBlank() }, address.address)
        else MailAddress(address.toString(), null)

    private fun decode(value: String): String =
        runCatching { MimeUtility.decodeText(MimeUtility.unfold(value)) }.getOrDefault(value)

    private class Collector {
        var html: String? = null
        var text: String? = null
        val inline = LinkedHashMap<String, MailAttachment>()
        val locations = LinkedHashMap<String, MailAttachment>()
        val attachments = ArrayList<MailAttachment>()

        fun walk(part: MimePart) {
            when {
                part.isMimeType("multipart/*") -> {
                    val multipart = MimeMultipart(MimePartDataSource(part))
                    val children = (0 until multipart.count).map { multipart.getBodyPart(it) as MimePart }
                    if (part.isMimeType("multipart/alternative")) {
                        // Von hinten nach vorn: die letzte Alternative ist die bevorzugte, Text aber trotzdem mitnehmen.
                        children.reversed().forEach(::walk)
                    } else {
                        children.forEach(::walk)
                    }
                }
                isAttachment(part) -> addAttachment(part)
                part.isMimeType("text/html") -> if (html == null) html = readText(part) else addAttachment(part)
                part.isMimeType("text/plain") -> if (text == null) text = readText(part) else addAttachment(part)
                else -> addAttachment(part)
            }
        }

        private fun isAttachment(part: MimePart): Boolean =
            Part.ATTACHMENT.equals(runCatching { part.disposition }.getOrNull(), ignoreCase = true)

        private fun addAttachment(part: MimePart) {
            val data = part.inputStream.use { it.readBytes() }
            val mimeType = runCatching { ContentType(part.contentType).baseType.lowercase() }.getOrDefault("application/octet-stream")
            val name = runCatching { part.fileName }.getOrNull()?.let(::decode)
                ?: if (mimeType == "message/rfc822") embeddedName(data) else MailViewBundle.message("attachment.unnamed")
            val attachment = MailAttachment(name, mimeType, data)
            val contentId = runCatching { part.contentID }.getOrNull()?.trim()?.removePrefix("<")?.removeSuffix(">")
            val location = runCatching { part.getHeader("Content-Location", null) }.getOrNull()?.let(MimeUtility::unfold)?.trim()
            when {
                isAttachment(part) -> attachments += attachment
                contentId != null -> inline[contentId] = attachment
                !location.isNullOrEmpty() -> locations[location] = attachment
                else -> attachments += attachment
            }
        }

        /** Angehängte Mails haben meist keinen Dateinamen; dann den Betreff nehmen, damit mehrere unterscheidbar sind. */
        private fun embeddedName(data: ByteArray): String {
            val subject = runCatching { MimeMessage(null, ByteArrayInputStream(data)).subject }.getOrNull()
                ?.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), " ")?.trim()?.take(80)
            return "${subject?.takeIf { it.isNotEmpty() } ?: "message"}.eml"
        }

        private fun readText(part: MimePart): String {
            val bytes = part.inputStream.use { it.readBytes() }
            val charset = runCatching {
                ContentType(part.contentType).getParameter("charset")?.let { Charset.forName(MimeUtility.javaCharset(it)) }
            }.getOrNull() ?: Charsets.UTF_8
            return String(bytes, charset)
        }
    }
}
