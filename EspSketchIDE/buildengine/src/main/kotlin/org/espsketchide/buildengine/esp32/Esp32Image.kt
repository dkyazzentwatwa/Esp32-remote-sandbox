package org.espsketchide.buildengine.esp32

import org.espsketchide.buildengine.BuildException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * ESP32 firmware images: ELF to the `.bin` the bootloader loads (esptool's `elf2image`) and
 * several images merged into one flash image (`merge-bin`). Follows ESP-IDF's documented app
 * image format; esptool was read as a behavioural reference so the output is byte-identical.
 */
object Esp32Image {

    val FLASH_MODES = mapOf("qio" to 0, "qout" to 1, "dio" to 2, "dout" to 3)
    val FLASH_SIZES = mapOf(
        "1MB" to 0x00, "2MB" to 0x10, "4MB" to 0x20, "8MB" to 0x30,
        "16MB" to 0x40, "32MB" to 0x50, "64MB" to 0x60, "128MB" to 0x70,
    )
    val FLASH_FREQS = mapOf("80m" to 0xF, "40m" to 0x0, "26m" to 0x1, "20m" to 0x2)

    private const val MAGIC = 0xE9
    private const val CHECKSUM_SEED = 0xEF
    private const val WP_PIN_DISABLED = 0xEE
    private const val CHIP_ID_ESP32 = 0
    private const val SEG_HEADER = 8
    private const val MMU_PAGE = 0x10000

    /** ESP32 memory map; segments only merge when their address falls in the same ranges. */
    private val MEMORY_MAP = listOf(
        0x00000000L to 0x00010000L, 0x3F400000L to 0x3F800000L, 0x3F800000L to 0x3FC00000L,
        0x3FF80000L to 0x3FF82000L, 0x3FF90000L to 0x40000000L, 0x3FFAE000L to 0x40000000L,
        0x3FFE0000L to 0x3FFFFFFCL, 0x40000000L to 0x40070000L, 0x40070000L to 0x40078000L,
        0x40078000L to 0x40080000L, 0x40080000L to 0x400A0000L, 0x400A0000L to 0x400BFFFCL,
        0x400C0000L to 0x400C2000L, 0x400D0000L to 0x40400000L, 0x50000000L to 0x50002000L,
    )

    private fun isFlashAddress(addr: Long) = addr in 0x400D0000L until 0x40400000L || addr in 0x3F400000L until 0x3F800000L

    private fun memoryTypes(addr: Long) = MEMORY_MAP.indices.filter { addr >= MEMORY_MAP[it].first && addr < MEMORY_MAP[it].second }

    private class Segment(var addr: Long, var data: ByteArray, val name: String)

    class Options(
        val flashMode: String,
        val flashFreq: String,
        val flashSize: String,
        /** Where to patch the ELF's SHA-256 (Arduino passes 0xb0, the app description). */
        val elfSha256Offset: Int? = null,
        val minRevFull: Int = 0,
        val maxRevFull: Int = 0xFFFF,
    )

    fun elf2image(elfBytes: ByteArray, options: Options): ByteArray {
        val elf = Elf32.parse(elfBytes)
        val mode = FLASH_MODES[options.flashMode] ?: throw BuildException("Unknown flash mode ${options.flashMode}")
        val size = FLASH_SIZES[options.flashSize] ?: throw BuildException("Unknown flash size ${options.flashSize}")
        val freq = FLASH_FREQS[options.flashFreq] ?: throw BuildException("Unknown flash frequency ${options.flashFreq}")

        // Loadable sections in ELF order, each padded to 4 bytes.
        var segments = elf.sections
            .filter { it.type in PROG_TYPES && it.addr != 0L && it.size > 0 }
            .map { Segment(it.addr, padTo(it.data, 4), it.name) }
        segments = mergeAdjacent(segments)

        val sha256Offset = options.elfSha256Offset
            ?: if (segments.any { it.name.contains(".flash.appdesc") && isFlashAddress(it.addr) }) 0xB0 else null
        val elfSha256 = sha256Offset?.let { sha256(elfBytes) }

        val out = ByteArrayOutputStream()
        // Common header (segment count rewritten at the end) + 16-byte extended header.
        out.write(MAGIC); out.write(0); out.write(mode); out.write(size + freq)
        out.write(le32(elf.entry))
        out.write(WP_PIN_DISABLED); out.write(0); out.write(0); out.write(0)
        out.write(le16(CHIP_ID_ESP32)); out.write(0) // min_rev (legacy)
        out.write(le16(options.minRevFull)); out.write(le16(options.maxRevFull))
        out.write(ByteArray(4)); out.write(1) // hash appended

        var checksum = CHECKSUM_SEED
        var count = 0
        fun write(seg: Segment) {
            var data = seg.data
            if (sha256Offset != null) data = patchSha256(data, out.size(), sha256Offset, elfSha256!!)
            if (data.size % 4 != 0) throw BuildException("Segment ${seg.name} length isn't a multiple of 4")
            out.write(le32(seg.addr)); out.write(le32(data.size.toLong())); out.write(data)
            checksum = data.fold(checksum) { acc, b -> acc xor (b.toInt() and 0xFF) }
            count++
        }

        val flash = segments.filter { isFlashAddress(it.addr) }.sortedBy { it.addr }.toMutableList()
        val ram = segments.filter { !isFlashAddress(it.addr) }.sortedBy { it.addr }.toMutableList()
        flash.indexOfFirst { it.name == ".flash.appdesc" }.takeIf { it > 0 }?.let { flash.add(0, flash.removeAt(it)) }
        ram.indexOfFirst { it.name == ".dram0.bootdesc" }.takeIf { it > 0 }?.let { ram.add(0, ram.removeAt(it)) }
        flash.zipWithNext().forEach { (a, b) ->
            if (b.addr / MMU_PAGE == a.addr / MMU_PAGE) {
                throw BuildException("Segments at 0x%x and 0x%x share a 64 KB flash page".format(a.addr, b.addr))
            }
        }

        while (flash.isNotEmpty()) {
            val seg = flash.first()
            val pad = paddingNeeded(out.size(), seg.addr)
            if (pad > 0) {
                // Fill the gap with the start of a RAM segment if possible, zeros otherwise.
                val filler = if (ram.isNotEmpty() && pad > SEG_HEADER) {
                    val r = ram.first()
                    val part = Segment(r.addr, r.data.copyOf(minOf(pad, r.data.size)), r.name)
                    r.data = r.data.copyOfRange(part.data.size, r.data.size)
                    r.addr += part.data.size
                    if (r.data.isEmpty()) ram.removeAt(0)
                    part
                } else Segment(0, ByteArray(pad), "padding")
                write(filler)
            } else {
                // ESP-IDF's 2nd stage bootloader doesn't map the last MMU page if a flash
                // segment ends less than 0x24 bytes into it; pad such segments.
                val end = out.size() + seg.data.size + SEG_HEADER
                val remainder = end % MMU_PAGE
                if (remainder < 0x24) seg.data = seg.data + ByteArray(0x24 - remainder)
                write(seg)
                flash.removeAt(0)
            }
        }
        ram.forEach(::write)

        // Checksum in the last byte of a 16-byte block.
        while (out.size() % 16 != 15) out.write(0)
        out.write(checksum)
        val image = out.toByteArray()
        image[1] = count.toByte()
        return image + sha256(image)
    }

    /**
     * Places [images] (offset to data) into one flash image starting at 0, gaps and tail filled
     * with 0xFF up to [padToSize] (like `esptool merge-bin --flash-* keep`).
     */
    fun mergeBin(images: List<Pair<Int, ByteArray>>, padToSize: Int? = null): ByteArray {
        val sorted = images.sortedBy { it.first }
        sorted.zipWithNext().forEach { (a, b) ->
            if (a.first + a.second.size > b.first) throw BuildException("Images at 0x%x and 0x%x overlap".format(a.first, b.first))
        }
        val end = sorted.last().let { it.first + it.second.size }
        val total = maxOf(end, padToSize ?: 0)
        val out = ByteArray(total) { 0xFF.toByte() }
        sorted.forEach { (offset, data) -> data.copyInto(out, offset) }
        return out
    }

    /** Bytes of padding segment needed so the next segment's data lands at [addr] mod 64 KiB. */
    private fun paddingNeeded(filePos: Int, addr: Long): Int {
        val alignPast = (addr % MMU_PAGE).toInt() - SEG_HEADER
        var pad = (MMU_PAGE - filePos % MMU_PAGE) + alignPast
        if (pad == 0 || pad == MMU_PAGE) return 0
        pad -= SEG_HEADER
        if (pad < 0) pad += MMU_PAGE
        return pad
    }

    /** Merges each section into the previous one when it continues it in the same memory type. */
    private fun mergeAdjacent(segments: List<Segment>): List<Segment> {
        if (segments.isEmpty()) return segments
        val result = ArrayDeque<Segment>()
        for (i in segments.size - 1 downTo 1) {
            val prev = segments[i - 1]
            val next = segments[i]
            if (memoryTypes(prev.addr) == memoryTypes(next.addr) && next.addr == prev.addr + prev.data.size) {
                prev.data = prev.data + next.data
            } else {
                result.addFirst(next)
            }
        }
        result.addFirst(segments[0])
        return result.toList()
    }

    private fun patchSha256(data: ByteArray, filePos: Int, offset: Int, sha: ByteArray): ByteArray {
        if (offset < filePos || offset >= filePos + data.size + SEG_HEADER) return data
        val at = offset - filePos - SEG_HEADER
        if (at < 0 || at + sha.size > data.size) throw BuildException("ELF SHA-256 offset 0x%x straddles a segment".format(offset))
        if (data.copyOfRange(at, at + sha.size).any { it.toInt() != 0 }) {
            throw BuildException("Bytes at ELF SHA-256 offset 0x%x are not zero".format(offset))
        }
        return data.copyOf().also { sha.copyInto(it, at) }
    }

    private val PROG_TYPES = setOf(0x01, 0x0E, 0x0F, 0x10) // PROGBITS, INIT/FINI/PREINIT_ARRAY

    private fun padTo(data: ByteArray, align: Int) = if (data.size % align == 0) data else data.copyOf((data.size + align - 1) / align * align)

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    private fun le32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}

/** Just enough ELF32 (little-endian) reading for elf2image: entry point and sections. */
class Elf32 private constructor(val entry: Long, val sections: List<Section>) {

    class Section(val name: String, val type: Int, val addr: Long, val size: Int, val data: ByteArray)

    companion object {
        fun parse(bytes: ByteArray): Elf32 {
            if (bytes.size < 52 || bytes[0] != 0x7F.toByte() || String(bytes, 1, 3) != "ELF") throw BuildException("Not an ELF file")
            if (bytes[4].toInt() != 1 || bytes[5].toInt() != 1) throw BuildException("Only 32-bit little-endian ELF is supported")
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val entry = bb.getInt(24).toLong() and 0xFFFFFFFFL
            val shoff = bb.getInt(32)
            val shentsize = bb.getShort(46).toInt() and 0xFFFF
            val shnum = bb.getShort(48).toInt() and 0xFFFF
            val shstrndx = bb.getShort(50).toInt() and 0xFFFF
            fun header(i: Int) = shoff + i * shentsize
            val strOff = bb.getInt(header(shstrndx) + 16)
            fun name(offset: Int): String {
                var end = strOff + offset
                while (bytes[end].toInt() != 0) end++
                return String(bytes, strOff + offset, end - strOff - offset, Charsets.US_ASCII)
            }
            val sections = (0 until shnum).map { i ->
                val h = header(i)
                val type = bb.getInt(h + 4)
                val addr = bb.getInt(h + 12).toLong() and 0xFFFFFFFFL
                val offset = bb.getInt(h + 16)
                val size = bb.getInt(h + 20)
                val data = if (type == 8 /* NOBITS */ || size <= 0) ByteArray(0) else bytes.copyOfRange(offset, offset + size)
                Section(name(bb.getInt(h)), type, addr, size, data)
            }
            return Elf32(entry, sections)
        }
    }
}
