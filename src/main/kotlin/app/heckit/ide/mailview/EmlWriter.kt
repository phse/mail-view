package app.heckit.ide.mailview

import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.InternetHeaders
import jakarta.mail.internet.MailDateFormat
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.UUID

/**
 * Schreibt eine [ParsedMail] als MIME-Text (.eml), z. B. für in .msg angehängte Mails. Von Hand statt mit
 * MimeMessage.writeTo: das bräuchte wie getContent() die Mailcap-Registrierung von Jakarta Activation.
 */
object EmlWriter {
    /** Kopfzeilen, die aus der Mail neu geschrieben werden; die übrigen (Received, Authentication-Results …) bleiben. */
    private val GENERATED = setOf("subject", "from", "to", "cc", "bcc", "reply-to", "date", "mime-version")

    fun write(mail: ParsedMail): ByteArray {
        val out = StringBuilder()
        mail.headers?.let { raw ->
            runCatching { InternetHeaders(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))) }.getOrNull()?.allHeaderLines?.toList()
                ?.filter { line -> line.substringBefore(':').trim().lowercase().let { it !in GENERATED && !it.startsWith("content-") } }
                ?.forEach { out.append(it).append("\r\n") }
        }
        mail.subject?.let { out.header("Subject", MimeUtility.encodeText(it, "UTF-8", null)) }
        out.addresses("From", mail.from)
        out.addresses("To", mail.to)
        out.addresses("Cc", mail.cc)
        out.addresses("Bcc", mail.bcc)
        out.addresses("Reply-To", mail.replyTo)
        mail.date?.let { out.header("Date", MailDateFormat().format(it)) }
        out.header("MIME-Version", "1.0")

        val alternatives = listOfNotNull(
            mail.text?.let { textPart("text/plain", it) },
            mail.html?.let { textPart("text/html", it) },
        ).ifEmpty { listOf(textPart("text/plain", "")) }
        var body = if (alternatives.size == 1) alternatives[0] else multipart("alternative", alternatives)
        if (mail.inlineParts.isNotEmpty()) {
            body = multipart("related", listOf(body) + mail.inlineParts.map { (cid, part) -> binaryPart(part, "inline", cid) })
        }
        if (mail.attachments.isNotEmpty()) {
            body = multipart("mixed", listOf(body) + mail.attachments.map { binaryPart(it, "attachment", null) })
        }
        out.append(body)
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun StringBuilder.header(name: String, value: String) = append(name).append(": ").append(value).append("\r\n")

    private fun StringBuilder.addresses(name: String, list: List<MailAddress>) {
        if (list.isEmpty()) return
        header(name, list.joinToString(", ") { a ->
            runCatching { InternetAddress(a.address ?: "", a.name, "UTF-8").toString() }.getOrDefault(a.address ?: a.name ?: "")
        })
    }

    private fun textPart(type: String, text: String) =
        "Content-Type: $type; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n${base64(text.toByteArray(Charsets.UTF_8))}"

    private fun binaryPart(part: MailAttachment, disposition: String, contentId: String?): String {
        val name = MimeUtility.encodeText(part.name, "UTF-8", "B").replace("\"", "")
        return buildString {
            append("Content-Type: ${part.mimeType}; name=\"$name\"\r\n")
            append("Content-Disposition: $disposition; filename=\"$name\"\r\n")
            contentId?.let { append("Content-ID: <$it>\r\n") }
            // message/rfc822 darf laut RFC 2046 nicht base64-kodiert sein.
            if (part.mimeType == "message/rfc822") append("Content-Transfer-Encoding: 8bit\r\n\r\n").append(String(part.data, Charsets.UTF_8))
            else append("Content-Transfer-Encoding: base64\r\n\r\n").append(base64(part.data))
        }
    }

    private fun multipart(subtype: String, parts: List<String>): String {
        val boundary = "----=_MailView_${UUID.randomUUID()}"
        return buildString {
            append("Content-Type: multipart/$subtype; boundary=\"$boundary\"\r\n\r\n")
            for (part in parts) append("--$boundary\r\n").append(part).append("\r\n")
            append("--$boundary--\r\n")
        }
    }

    private fun base64(data: ByteArray): String = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(data) + "\r\n"
}
