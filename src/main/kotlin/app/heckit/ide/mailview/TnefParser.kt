package app.heckit.ide.mailview

import org.bbottema.rtftohtml.impl.RTF2HTMLConverterRFCCompliant
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** Inhalt einer winmail.dat: Text (falls Outlook ihn mit eingepackt hat) und die eigentlichen Anhänge. */
class TnefContent(
    val subject: String?,
    val date: Date?,
    val html: String?,
    val text: String?,
    val inlineParts: Map<String, MailAttachment>,
    val attachments: List<MailAttachment>,
)

/**
 * Liest TNEF (MS-OXTNEF), das Format von winmail.dat: Outlook packt Anhänge und formatierten Text dort hinein,
 * andere Mailprogramme zeigen nur die eine Datei. Längenangaben werden gegen die Dateigröße geprüft.
 */
object TnefParser {
    private const val SIGNATURE = 0x223E9F78

    // Attribute (Kennung = Typ << 16 | ID)
    private const val ATT_SUBJECT = 0x00018004
    private const val ATT_DATE_SENT = 0x00038005
    private const val ATT_BODY = 0x0002800C
    private const val ATT_ATTACH_DATA = 0x0006800F
    private const val ATT_ATTACH_TITLE = 0x00018010
    private const val ATT_ATTACH_RENDDATA = 0x00069002
    private const val ATT_MAPI_PROPS = 0x00069003
    private const val ATT_ATTACHMENT = 0x00069005
    private const val ATT_OEM_CODEPAGE = 0x00069007

    private const val LEVEL_MESSAGE = 1
    private const val LEVEL_ATTACHMENT = 2

    // MAPI-Eigenschaften (MS-OXPROPS)
    private const val PR_SUBJECT = 0x0037
    private const val PR_CLIENT_SUBMIT_TIME = 0x0039
    private const val PR_BODY = 0x1000
    private const val PR_RTF_COMPRESSED = 0x1009
    private const val PR_BODY_HTML = 0x1013
    private const val PR_DISPLAY_NAME = 0x3001
    private const val PR_ATTACH_DATA = 0x3701
    private const val PR_ATTACH_FILENAME = 0x3704
    private const val PR_ATTACH_LONG_FILENAME = 0x3707
    private const val PR_ATTACH_MIME_TAG = 0x370E
    private const val PR_ATTACH_CONTENT_ID = 0x3712
    private const val PR_INTERNET_CODEPAGE = 0x3FDE

    fun isTnef(bytes: ByteArray): Boolean =
        bytes.size >= 6 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == SIGNATURE

    fun parse(bytes: ByteArray): TnefContent {
        if (!isTnef(bytes)) throw IOException("Not a TNEF file")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 6 // Signatur und Schlüssel
        var codepage: Charset = Charset.forName("windows-1252")
        var subject: String? = null
        var date: Date? = null
        var body: String? = null
        var messageProps: Map<Int, Any> = emptyMap()
        val attachments = ArrayList<RawAttachment>()

        while (pos + 9 <= bytes.size) {
            val level = bytes[pos].toInt() and 0xFF
            val id = buffer.getInt(pos + 1)
            val length = buffer.getInt(pos + 5).toLong() and 0xFFFFFFFFL
            val start = pos + 9
            if (length > bytes.size - start) break // abgeschnitten oder kaputt
            val data = bytes.copyOfRange(start, start + length.toInt())
            pos = start + length.toInt() + 2 // Prüfsumme

            if (level == LEVEL_MESSAGE) when (id) {
                ATT_OEM_CODEPAGE -> if (data.size >= 4) codepage = charset(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(0))
                ATT_SUBJECT -> subject = cString(data, codepage)
                ATT_BODY -> body = cString(data, codepage)
                ATT_DATE_SENT -> date = dtr(data)
                ATT_MAPI_PROPS -> messageProps = runCatching { mapiProps(data, codepage) }.getOrDefault(emptyMap())
            } else if (level == LEVEL_ATTACHMENT) {
                if (id == ATT_ATTACH_RENDDATA || attachments.isEmpty()) attachments += RawAttachment()
                val current = attachments.last()
                when (id) {
                    ATT_ATTACH_TITLE -> current.title = cString(data, codepage)
                    ATT_ATTACH_DATA -> current.data = data
                    ATT_ATTACHMENT -> current.props = runCatching { mapiProps(data, codepage) }.getOrDefault(emptyMap())
                }
            }
        }

        val html = html(messageProps)
        val inline = LinkedHashMap<String, MailAttachment>()
        val files = ArrayList<MailAttachment>()
        for (raw in attachments) {
            val data = raw.data ?: (raw.props[PR_ATTACH_DATA] as? ByteArray) ?: continue
            val name = (raw.props[PR_ATTACH_LONG_FILENAME] ?: raw.props[PR_ATTACH_FILENAME] ?: raw.props[PR_DISPLAY_NAME]) as? String
                ?: raw.title ?: MailViewBundle.message("attachment.unnamed")
            val attachment = MailAttachment(name, (raw.props[PR_ATTACH_MIME_TAG] as? String)?.lowercase() ?: "application/octet-stream", data)
            val contentId = (raw.props[PR_ATTACH_CONTENT_ID] as? String)?.trim()?.removePrefix("<")?.removeSuffix(">")
            if (contentId != null && html?.contains("cid:$contentId", ignoreCase = true) == true) inline[contentId] = attachment
            else files += attachment
        }
        return TnefContent(
            subject = (messageProps[PR_SUBJECT] as? String) ?: subject,
            date = (messageProps[PR_CLIENT_SUBMIT_TIME] as? Date) ?: date,
            html = html,
            text = (messageProps[PR_BODY] as? String) ?: body,
            inlineParts = inline,
            attachments = files,
        )
    }

    /** Wie bei .msg: HTML-Teil, sonst das gepackte RTF (enthält bei Outlook oft das HTML) umgewandelt. */
    private fun html(props: Map<Int, Any>): String? {
        when (val value = props[PR_BODY_HTML]) {
            is String -> return value.takeIf { it.isNotBlank() }
            is ByteArray -> {
                val charset = (props[PR_INTERNET_CODEPAGE] as? Int)?.let(::charset) ?: Charsets.UTF_8
                return String(value, charset).trimEnd('\u0000').takeIf { it.isNotBlank() }
            }
        }
        val rtf = props[PR_RTF_COMPRESSED] as? ByteArray ?: return null
        return runCatching {
            RTF2HTMLConverterRFCCompliant.INSTANCE.rtf2html(String(CompressedRtf.decompress(rtf), Charsets.ISO_8859_1))
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private class RawAttachment {
        var title: String? = null
        var data: ByteArray? = null
        var props: Map<Int, Any> = emptyMap()
    }

    /**
     * MAPI-Eigenschaftenliste (MS-OXTNEF 2.1.3.4): Anzahl, dann je Eigenschaft Typ, ID, ggf. Name und Wert(e),
     * alles auf 4 Byte aufgefüllt. Ergebnis: ID → String, ByteArray, Int, Long oder Date (nur Einzelwerte).
     */
    private fun mapiProps(data: ByteArray, codepage: Charset): Map<Int, Any> {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val result = HashMap<Int, Any>()
        fun need(n: Long) { if (n < 0 || b.remaining() < n) throw IOException("Truncated MAPI properties") }
        fun skipPadding(n: Int) { val pad = (4 - n % 4) % 4; need(pad.toLong()); b.position(b.position() + pad) }

        need(4)
        val count = b.int
        repeat(count) {
            need(4)
            val rawType = b.short.toInt() and 0xFFFF
            val id = b.short.toInt() and 0xFFFF
            if (id >= 0x8000) { // benannte Eigenschaft: GUID, Art, Nummer oder Name
                need(20)
                b.position(b.position() + 16)
                if (b.int == 0) { need(4); b.int } else {
                    need(4)
                    val len = b.int
                    need(len.toLong())
                    b.position(b.position() + len)
                    skipPadding(len)
                }
            }
            val multi = rawType and 0x1000 != 0
            val type = rawType and 0x0FFF
            val variable = type == 0x001E || type == 0x001F || type == 0x0102 || type == 0x000D
            val values = if (multi || variable) { need(4); b.int } else 1
            if (values < 0 || values > data.size) throw IOException("Bad value count")
            var value: Any? = null
            repeat(values) {
                if (variable) {
                    need(4)
                    val len = b.int
                    need(len.toLong())
                    val bytes = ByteArray(len).also { b.get(it) }
                    skipPadding(len)
                    value = when (type) {
                        0x001F -> String(bytes, Charsets.UTF_16LE).trimEnd('\u0000')
                        0x001E -> String(bytes, codepage).trimEnd('\u0000')
                        0x000D -> if (bytes.size > 16) bytes.copyOfRange(16, bytes.size) else bytes // IID vorweg
                        else -> bytes
                    }
                } else {
                    val size = when (type) { 0x0005, 0x0006, 0x0007, 0x0014, 0x0040 -> 8; 0x0048 -> 16; else -> 4 }
                    need(size.toLong())
                    value = when (type) {
                        0x0003, 0x000A, 0x000B, 0x0002 -> b.int
                        0x0014 -> b.long
                        0x0040 -> b.long.takeIf { it > 0 }?.let { Date(it / 10_000 - 11_644_473_600_000L) }
                        else -> { b.position(b.position() + size); null }
                    }
                }
            }
            if (!multi) value?.let { result[id] = it }
        }
        return result
    }

    private fun cString(data: ByteArray, charset: Charset): String? {
        val end = data.indexOf(0).let { if (it < 0) data.size else it }
        return String(data, 0, end, charset).trim().takeIf { it.isNotEmpty() }
    }

    /** DTR: Jahr, Monat, Tag, Stunde, Minute, Sekunde, Wochentag (je 16 Bit), in UTC. */
    private fun dtr(data: ByteArray): Date? {
        if (data.size < 12) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val v = IntArray(6) { b.short.toInt() }
        return runCatching {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(v[0], v[1] - 1, v[2], v[3], v[4], v[5])
            }.time
        }.getOrNull()
    }

    private fun charset(codepage: Int): Charset = MsgParser.charset(codepage)
}
