package app.heckit.ide.mailview

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Entpackt den RTF-Inhalt einer .msg (PR_RTF_COMPRESSED, MS-OXRTFCP): LZFu oder unkomprimiert (MELA). */
object CompressedRtf {
    private const val LZFU = 0x75465A4C
    private const val MELA = 0x414C454D
    private val PREFILL = ("{\\rtf1\\ansi\\mac\\deff0\\deftab720{\\fonttbl;}{\\f0\\fnil \\froman \\fswiss \\fmodern \\fscript " +
        "\\fdecor MS Sans SerifSymbolArialTimes New RomanCourier{\\colortbl\\red0\\green0\\blue0\r\n\\par " +
        "\\pard\\plain\\f0\\fs20\\b\\i\\u\\tab\\tx").toByteArray(Charsets.US_ASCII)

    fun decompress(data: ByteArray): ByteArray {
        if (data.size < 16) throw IOException("Compressed RTF too short")
        val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val compressedSize = header.getInt(0)
        val rawSize = header.getInt(4)
        val end = minOf(data.size, compressedSize + 4)
        return when (header.getInt(8)) {
            MELA -> data.copyOfRange(16, minOf(data.size, 16 + rawSize))
            LZFU -> lzfu(data, end, rawSize)
            else -> throw IOException("Unknown compressed RTF type")
        }
    }

    private fun lzfu(data: ByteArray, end: Int, rawSize: Int): ByteArray {
        val dictionary = ByteArray(4096)
        PREFILL.copyInto(dictionary)
        var write = PREFILL.size
        val out = ByteArrayOutputStream(maxOf(rawSize, 0))
        var pos = 16
        while (pos < end) {
            val control = data[pos++].toInt() and 0xFF
            for (bit in 0 until 8) {
                if (pos >= end) break
                if (control and (1 shl bit) == 0) {
                    val b = data[pos++]
                    out.write(b.toInt())
                    dictionary[write] = b
                    write = (write + 1) and 0xFFF
                } else {
                    if (pos + 1 >= end) return out.toByteArray()
                    val word = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos += 2
                    val offset = word shr 4
                    val length = (word and 0xF) + 2
                    // Verweis auf die aktuelle Schreibposition markiert das Ende.
                    if (offset == write) return out.toByteArray()
                    for (i in 0 until length) {
                        val b = dictionary[(offset + i) and 0xFFF]
                        out.write(b.toInt())
                        dictionary[write] = b
                        write = (write + 1) and 0xFFF
                    }
                }
            }
        }
        return out.toByteArray()
    }
}
