package app.heckit.ide.mailview

import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.InternetHeaders
import org.bbottema.rtftohtml.impl.RTF2HTMLConverterRFCCompliant
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Date

/**
 * Liest Outlook-.msg (MS-OXMSG) mit dem eigenen OLE2-Leser [CfbReader]. Hat die Mail nur einen
 * RTF-Inhalt, wird er entpackt ([CompressedRtf]) und nach HTML umgewandelt.
 */
object MsgParser {
    private val OLE_SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    // Eigenschafts-IDs (MS-OXPROPS)
    private const val SUBJECT = 0x0037
    private const val CLIENT_SUBMIT_TIME = 0x0039
    private const val TRANSPORT_HEADERS = 0x007D
    private const val SENDER_NAME = 0x0C1A
    private const val SENDER_ADDRESS_TYPE = 0x0C1E
    private const val SENDER_EMAIL = 0x0C1F
    private const val RECIPIENT_TYPE = 0x0C15
    private const val DELIVERY_TIME = 0x0E06
    private const val BODY = 0x1000
    private const val RTF_COMPRESSED = 0x1009
    private const val BODY_HTML = 0x1013
    private const val DISPLAY_NAME = 0x3001
    private const val ADDRESS_TYPE = 0x3002
    private const val EMAIL_ADDRESS = 0x3003
    private const val ATTACH_DATA = 0x3701
    private const val ATTACH_FILENAME = 0x3704
    private const val ATTACH_METHOD = 0x3705
    private const val ATTACH_LONG_FILENAME = 0x3707
    private const val ATTACH_MIME_TAG = 0x370E
    private const val ATTACH_CONTENT_ID = 0x3712
    private const val SMTP_ADDRESS = 0x39FE
    private const val INTERNET_CODEPAGE = 0x3FDE
    private const val MESSAGE_CODEPAGE = 0x3FFD
    private const val SENDER_SMTP_ADDRESS = 0x5D01
    private const val SENT_REPRESENTING_EMAIL = 0x0065
    private const val SENT_REPRESENTING_SMTP_ADDRESS = 0x5D02
    private const val LAST_MODIFIER_NAME = 0x3FFA

    private const val ATTACH_EMBEDDED_MESSAGE = 5

    fun isMsg(bytes: ByteArray): Boolean =
        bytes.size >= OLE_SIGNATURE.size && OLE_SIGNATURE.indices.all { bytes[it] == OLE_SIGNATURE[it] }

    fun parse(bytes: ByteArray): ParsedMail = parse(CfbReader(bytes).root, headerSize = 32)

    /** [headerSize]: 32 für die Mail selbst, 24 für angehängte Mails (MS-OXMSG 2.4.1). */
    private fun parse(root: CfbEntry, headerSize: Int): ParsedMail {
        val message = Props(root, headerSize, codepage = null)
        // 8-Bit-Texte: Codepage der Mail, sonst die für den Internet-Versand.
        val codepage = (message.int(MESSAGE_CODEPAGE) ?: message.int(INTERNET_CODEPAGE))?.let(::charset)
        val props = Props(root, headerSize, codepage)
        // Nur bei empfangenen Mails vorhanden, nicht bei Entwürfen oder gesendeten Mails.
        val headers = props.string(TRANSPORT_HEADERS)?.trim()?.takeIf { it.isNotEmpty() }
        val parsedHeaders = headers?.let { runCatching { InternetHeaders(ByteArrayInputStream(it.toByteArray(Charsets.UTF_8))) }.getOrNull() }

        val from = listOfNotNull(sender(props, parsedHeaders))
        val recipients = root.children.filterKeys { it.startsWith("__recip_version1.0_") }.values.map { Props(it, 8, codepage) }
        fun recipientsOf(type: Int) = recipients.filter { (it.int(RECIPIENT_TYPE) ?: 1) == type }.mapNotNull(::recipient)
        val replyTo = headerAddresses(parsedHeaders, "Reply-To").filter { r -> from.none { it.address.equals(r.address, ignoreCase = true) } }

        val html = html(props)
        val inline = LinkedHashMap<String, MailAttachment>()
        val attachments = ArrayList<MailAttachment>()
        root.children.filterKeys { it.startsWith("__attach_version1.0_") }.values.forEach { storage ->
            val attach = Props(storage, 8, codepage)
            if (attach.int(ATTACH_METHOD) == ATTACH_EMBEDDED_MESSAGE) {
                // Angehängte Mails liegen als eigener Ordner vor; als .eml weitergereicht, öffnen sie wieder in Mail View.
                val embedded = storage.child("__substg1.0_3701000D")?.takeIf { it.isStorage } ?: return@forEach
                val mail = runCatching { parse(embedded, headerSize = 24) }.getOrNull() ?: return@forEach
                attachments += MailAttachment(embeddedName(mail.subject ?: attach.string(DISPLAY_NAME)), "message/rfc822", EmlWriter.write(mail))
                return@forEach
            }
            val data = attach.binary(ATTACH_DATA) ?: return@forEach
            val attachment = MailAttachment(
                name = attach.string(ATTACH_LONG_FILENAME) ?: attach.string(ATTACH_FILENAME) ?: attach.string(DISPLAY_NAME)
                    ?: MailViewBundle.message("attachment.unnamed"),
                mimeType = attach.string(ATTACH_MIME_TAG)?.lowercase() ?: "application/octet-stream",
                data = data,
            )
            val contentId = attach.string(ATTACH_CONTENT_ID)?.trim()?.removePrefix("<")?.removeSuffix(">")
            if (contentId != null && html?.contains("cid:$contentId") == true) inline[contentId] = attachment
            else attachments += attachment
        }

        return ParsedMail(
            subject = props.string(SUBJECT),
            from = from,
            to = recipientsOf(1),
            cc = recipientsOf(2),
            bcc = recipientsOf(3),
            replyTo = replyTo,
            date = props.time(CLIENT_SUBMIT_TIME) ?: props.time(DELIVERY_TIME),
            html = html,
            text = props.string(BODY),
            inlineParts = inline,
            attachments = attachments,
            headers = headers,
        )
    }

    private fun embeddedName(subject: String?): String {
        val clean = subject?.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), " ")?.trim()?.take(80)
        return "${clean?.takeIf { it.isNotEmpty() } ?: "message"}.eml"
    }

    private fun html(props: Props): String? {
        props.binary(BODY_HTML)?.let { bytes ->
            val charset = props.int(INTERNET_CODEPAGE)?.let(::charset) ?: Charsets.UTF_8
            return String(bytes, charset).trimEnd('\u0000').takeIf { it.isNotBlank() }
        }
        props.string(BODY_HTML)?.let { return it }
        val rtf = props.binary(RTF_COMPRESSED) ?: return null
        return runCatching {
            val text = String(CompressedRtf.decompress(rtf), Charsets.ISO_8859_1)
            RTF2HTMLConverterRFCCompliant.INSTANCE.rtf2html(text)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Bei Exchange-Absendern ist die Adresse ein X.500-Pfad; dann die SMTP-Adresse oder den From-Header nehmen.
     * Gesendete Mails ohne Transport-Header (aus „Gesendete Elemente“ gezogen) tragen die SMTP-Adresse oft nur noch
     * als letzten Bearbeiter - als letzte Möglichkeit, das ist bei solchen Mails der Absender selbst. */
    private fun sender(props: Props, headers: InternetHeaders?): MailAddress? {
        val smtp = props.string(SENDER_SMTP_ADDRESS)?.takeIf(::isSmtp)
            ?: props.string(SENDER_EMAIL)?.takeIf { isSmtp(it) && !props.string(SENDER_ADDRESS_TYPE).equals("EX", ignoreCase = true) }
            ?: props.string(SENT_REPRESENTING_SMTP_ADDRESS)?.takeIf(::isSmtp)
            ?: props.string(SENT_REPRESENTING_EMAIL)?.takeIf(::isSmtp)
            ?: headerAddresses(headers, "From").firstOrNull()?.address?.takeIf(::isSmtp)
            ?: props.string(LAST_MODIFIER_NAME)?.takeIf(::isSmtp)
        return address(props.string(SENDER_NAME), smtp)
    }

    private fun recipient(props: Props): MailAddress? {
        val smtp = props.string(SMTP_ADDRESS)?.takeIf(::isSmtp)
            ?: props.string(EMAIL_ADDRESS)?.takeIf { isSmtp(it) && !props.string(ADDRESS_TYPE).equals("EX", ignoreCase = true) }
        return address(props.string(DISPLAY_NAME), smtp)
    }

    /** Ältere Mails tragen im Adressfeld teils den Anzeigenamen statt einer Adresse. */
    private fun isSmtp(value: String): Boolean = '@' in value && !value.startsWith("/")

    private fun headerAddresses(headers: InternetHeaders?, name: String): List<MailAddress> {
        val raw = headers?.getHeader(name, ", ") ?: return emptyList()
        return runCatching { InternetAddress.parseHeader(raw, false).map { MailAddress(it.personal, it.address) } }.getOrDefault(emptyList())
    }

    private fun address(name: String?, address: String?): MailAddress? {
        val cleanName = name?.trim()?.removeSurrounding("'")?.takeIf { it.isNotEmpty() && it != address }
        val cleanAddress = address?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleanName == null && cleanAddress == null) null else MailAddress(cleanName, cleanAddress)
    }

    /** Windows-Codepage → Java-Zeichensatz; unbekannte fallen auf windows-1252 zurück. */
    internal fun charset(codepage: Int): Charset {
        val name = when (codepage) {
            65001 -> "UTF-8"
            1200 -> "UTF-16LE"
            1201 -> "UTF-16BE"
            20127 -> "US-ASCII"
            in 28591..28605 -> "ISO-8859-${codepage - 28590}"
            932 -> "Shift_JIS"
            936 -> "GBK"
            949 -> "EUC-KR"
            950 -> "Big5"
            50220, 50221, 50222 -> "ISO-2022-JP"
            51932 -> "EUC-JP"
            20866 -> "KOI8-R"
            else -> "windows-$codepage"
        }
        return runCatching { Charset.forName(name) }.getOrDefault(Charset.forName("windows-1252"))
    }

    /** Eigenschaften eines Ordners: variable Werte als eigene Streams, feste in __properties_version1.0. */
    private class Props(private val storage: CfbEntry, headerSize: Int, private val codepage: Charset?) {
        private val fixed = HashMap<Int, Long>()

        init {
            storage.child("__properties_version1.0")?.data()?.let { data ->
                val view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                var pos = headerSize
                while (pos + 16 <= data.size) {
                    val tag = view.getInt(pos)
                    fixed[tag] = view.getLong(pos + 8)
                    pos += 16
                }
            }
        }

        private fun stream(id: Int, type: Int): ByteArray? = storage.child("__substg1.0_%04X%04X".format(id, type))?.data()

        fun string(id: Int): String? {
            val value = stream(id, 0x001F)?.let { String(it, Charsets.UTF_16LE) }
                ?: stream(id, 0x001E)?.let { String(it, codepage ?: Charset.forName("windows-1252")) }
            return value?.trimEnd('\u0000')?.takeIf { it.isNotBlank() }
        }

        fun binary(id: Int): ByteArray? = stream(id, 0x0102)

        fun int(id: Int): Int? = fixed[(id shl 16) or 0x0003]?.toInt()

        /** FILETIME (100 ns seit 1601) → Date. */
        fun time(id: Int): Date? = fixed[(id shl 16) or 0x0040]?.takeIf { it > 0 }?.let { Date(it / 10_000 - 11_644_473_600_000L) }
    }
}
