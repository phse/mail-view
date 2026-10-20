package app.heckit.ide.mailview

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Eintrag im OLE2-Container: ein Ordner (Storage) mit Kindern oder ein Stream mit Daten. */
class CfbEntry(val name: String, val isStorage: Boolean, private val reader: CfbReader?, private val start: Int, private val size: Long) {
    val children = LinkedHashMap<String, CfbEntry>()

    fun child(name: String): CfbEntry? = children[name]

    fun data(): ByteArray = reader?.read(start, size) ?: ByteArray(0)
}

/**
 * Nur lesender Parser für Compound File Binary (MS-CFB), das Containerformat von Outlook-.msg.
 * Unterstützt Version 3 (512-Byte-Sektoren) und 4 (4096), FAT mit DIFAT, Mini-FAT und Mini-Stream.
 */
class CfbReader(private val bytes: ByteArray) {
    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniStreamCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private var miniStream = ByteArray(0)
    val root: CfbEntry

    init {
        if (bytes.size < 512 || !MsgParser.isMsg(bytes)) throw IOException("Not an OLE2 compound file")
        sectorSize = 1 shl u16(0x1E)
        miniSectorSize = 1 shl u16(0x20)
        miniStreamCutoff = u32(0x38)
        fat = readFat()
        miniFat = readChain(i32(0x3C)).let(::toInts)
        root = readDirectory()
    }

    private fun u16(offset: Int): Int = buffer.getShort(offset).toInt() and 0xFFFF
    private fun i32(offset: Int): Int = buffer.getInt(offset)
    private fun u32(offset: Int): Long = buffer.getInt(offset).toLong() and 0xFFFFFFFFL

    private fun sectorOffset(sector: Int): Int = (sector + 1) * sectorSize

    private fun readFat(): IntArray {
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            val sector = i32(0x4C + i * 4)
            if (sector < 0) break
            fatSectors += sector
        }
        // Weitere FAT-Sektoren stehen in einer Kette von DIFAT-Sektoren (letzter Eintrag = nächster DIFAT-Sektor).
        var difat = i32(0x44)
        var guard = 0
        while (difat >= 0 && guard++ < MAX_SECTORS) {
            val base = sectorOffset(difat)
            if (base + sectorSize > bytes.size) break
            val perSector = sectorSize / 4 - 1
            for (i in 0 until perSector) {
                val sector = i32(base + i * 4)
                if (sector >= 0) fatSectors += sector
            }
            difat = i32(base + perSector * 4)
        }
        val fat = IntArray(fatSectors.size * sectorSize / 4)
        fatSectors.forEachIndexed { index, sector ->
            val base = sectorOffset(sector)
            for (i in 0 until sectorSize / 4) {
                val pos = base + i * 4
                fat[index * sectorSize / 4 + i] = if (pos + 4 <= bytes.size) i32(pos) else END_OF_CHAIN
            }
        }
        return fat
    }

    /** Liest eine Sektorkette der normalen FAT komplett. */
    private fun readChain(start: Int, limit: Long = Long.MAX_VALUE): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var sector = start
        var guard = 0
        while (sector >= 0 && sector < fat.size && out.size() < limit && guard++ < MAX_SECTORS) {
            val base = sectorOffset(sector)
            if (base >= bytes.size) break
            out.write(bytes, base, minOf(sectorSize, bytes.size - base))
            sector = fat[sector]
        }
        val data = out.toByteArray()
        return if (limit < data.size) data.copyOf(limit.toInt()) else data
    }

    private fun readMiniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var sector = start
        var guard = 0
        while (sector >= 0 && sector < miniFat.size && out.size() < size && guard++ < MAX_SECTORS) {
            val base = sector * miniSectorSize
            if (base >= miniStream.size) break
            out.write(miniStream, base, minOf(miniSectorSize, miniStream.size - base))
            sector = miniFat[sector]
        }
        val data = out.toByteArray()
        return if (size < data.size) data.copyOf(size.toInt()) else data
    }

    internal fun read(start: Int, size: Long): ByteArray =
        if (size < miniStreamCutoff) readMiniChain(start, size) else readChain(start, size)

    private fun toInts(data: ByteArray): IntArray {
        val ints = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return IntArray(ints.remaining()).also { ints.get(it) }
    }

    private class RawEntry(val name: String, val type: Int, val left: Int, val right: Int, val child: Int, val start: Int, val size: Long)

    private fun readDirectory(): CfbEntry {
        val dir = readChain(i32(0x30))
        val view = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        val raw = (0 until dir.size / 128).map { i ->
            val base = i * 128
            val nameLength = (view.getShort(base + 64).toInt() and 0xFFFF).coerceIn(0, 64)
            val name = String(dir, base, maxOf(0, nameLength - 2), Charsets.UTF_16LE)
            // Version 3 nutzt nur die unteren 32 Bit der Größe.
            val size = if (sectorSize == 512) view.getInt(base + 120).toLong() and 0xFFFFFFFFL else view.getLong(base + 120)
            RawEntry(name, dir[base + 66].toInt(), view.getInt(base + 68), view.getInt(base + 72), view.getInt(base + 76),
                view.getInt(base + 116), size)
        }
        val rootRaw = raw.firstOrNull { it.type == TYPE_ROOT } ?: throw IOException("OLE2 root entry missing")
        miniStream = readChain(rootRaw.start, rootRaw.size)
        val root = CfbEntry(rootRaw.name, true, null, 0, 0)
        val visited = HashSet<Int>()

        fun addSiblings(index: Int, parent: CfbEntry) {
            if (index < 0 || index >= raw.size || !visited.add(index)) return
            val entry = raw[index]
            addSiblings(entry.left, parent)
            when (entry.type) {
                TYPE_STORAGE -> CfbEntry(entry.name, true, null, 0, 0).also {
                    parent.children[entry.name] = it
                    addSiblings(entry.child, it)
                }
                TYPE_STREAM -> parent.children[entry.name] = CfbEntry(entry.name, false, this, entry.start, entry.size)
            }
            addSiblings(entry.right, parent)
        }
        addSiblings(rootRaw.child, root)
        return root
    }

    private companion object {
        const val END_OF_CHAIN = -2
        const val TYPE_STORAGE = 1
        const val TYPE_STREAM = 2
        const val TYPE_ROOT = 5
        const val MAX_SECTORS = 10_000_000
    }
}
