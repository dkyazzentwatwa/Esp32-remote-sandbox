package org.espsketchide.buildengine.esp32

import org.espsketchide.buildengine.BuildException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * ESP-IDF partition table: CSV (name, type, subtype, offset, size, flags) to the 3 KiB binary
 * the bootloader reads at 0x8000. Replaces the platform's gen_esp32part.py, following ESP-IDF's
 * documented format: 32-byte entries (magic AA 50), an MD5 entry (EB EB), 0xFF padding.
 */
object PartitionTable {

    data class Partition(val name: String, val type: Int, val subtype: Int, val offset: Int, val size: Int, val flags: Int)

    const val TABLE_OFFSET = 0x8000
    const val MAX_SIZE = 0xC00
    private const val ENTRY_SIZE = 32
    private const val APP_ALIGN = 0x10000
    private const val DATA_ALIGN = 0x1000

    private val TYPES = mapOf("app" to 0x00, "data" to 0x01, "bootloader" to 0x02, "partition_table" to 0x03)
    private val APP_SUBTYPES = mapOf("factory" to 0x00, "test" to 0x20) +
        (0..15).associate { "ota_$it" to 0x10 + it }
    private val DATA_SUBTYPES = mapOf(
        "ota" to 0x00, "phy" to 0x01, "nvs" to 0x02, "coredump" to 0x03, "nvs_keys" to 0x04,
        "efuse" to 0x05, "undefined" to 0x06, "esphttpd" to 0x80, "fat" to 0x81, "spiffs" to 0x82,
        "littlefs" to 0x83,
    )
    private val FLAGS = mapOf("encrypted" to 0x1, "readonly" to 0x2)

    fun parse(csv: String): List<Partition> {
        val result = mutableListOf<Partition>()
        var lastEnd = TABLE_OFFSET + DATA_ALIGN
        for ((index, raw) in csv.lines().withIndex()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val f = line.split(',').map { it.trim() }
            fun field(i: Int) = f.getOrElse(i) { "" }
            fun fail(msg: String): Nothing = throw BuildException("partitions.csv line ${index + 1}: $msg")

            val name = field(0).ifEmpty { fail("missing name") }
            if (name.toByteArray().size > 16) fail("name '$name' is longer than 16 bytes")
            val type = TYPES[field(1).lowercase()] ?: parseNumber(field(1)) ?: fail("unknown type '${field(1)}'")
            val subtypes = if (type == 0x00) APP_SUBTYPES else DATA_SUBTYPES
            val subtype = subtypes[field(2).lowercase()] ?: parseNumber(field(2))
                ?: if (field(2).isEmpty()) 0 else fail("unknown subtype '${field(2)}'")
            val align = if (type == 0x00) APP_ALIGN else DATA_ALIGN
            val offset = parseNumber(field(3)) ?: if (field(3).isEmpty()) roundUp(lastEnd, align) else fail("bad offset '${field(3)}'")
            if (offset % align != 0) fail("offset 0x%x of $name is not aligned to 0x%x".format(offset, align))
            val size = parseNumber(field(4)) ?: fail("bad size '${field(4)}'")
            val flags = field(5).split(':').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                .fold(0) { acc, fl -> acc or (FLAGS[fl] ?: fail("unknown flag '$fl'")) }
            result += Partition(name, type, subtype, offset, size, flags)
            lastEnd = offset + size
        }
        checkOverlaps(result)
        return result
    }

    fun toBinary(partitions: List<Partition>): ByteArray {
        val entries = ByteBuffer.allocate(partitions.size * ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        for (p in partitions) {
            entries.put(0xAA.toByte()).put(0x50)
            entries.put(p.type.toByte()).put(p.subtype.toByte())
            entries.putInt(p.offset).putInt(p.size)
            entries.put(p.name.toByteArray().copyOf(16))
            entries.putInt(p.flags)
        }
        val body = entries.array()
        val md5 = MessageDigest.getInstance("MD5").digest(body)
        val table = body + byteArrayOf(0xEB.toByte(), 0xEB.toByte()) + ByteArray(14) { 0xFF.toByte() } + md5
        if (table.size > MAX_SIZE) throw BuildException("Partition table is too large (${partitions.size} partitions)")
        return table + ByteArray(MAX_SIZE - table.size) { 0xFF.toByte() }
    }

    fun fromCsv(csv: String): ByteArray = toBinary(parse(csv))

    /** Decimal, 0x-hex, or with a K/M suffix ("1M", "0x10K"). Null if blank or unparseable. */
    fun parseNumber(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val multiplier = when (t.last().uppercaseChar()) { 'K' -> 1024; 'M' -> 1024 * 1024; else -> 1 }
        val digits = if (multiplier == 1) t else t.dropLast(1)
        val value = if (digits.startsWith("0x") || digits.startsWith("0X")) digits.substring(2).toLongOrNull(16)
        else digits.toLongOrNull()
        return value?.let { (it * multiplier).toInt() }
    }

    private fun roundUp(value: Int, align: Int) = (value + align - 1) / align * align

    private fun checkOverlaps(parts: List<Partition>) {
        val sorted = parts.sortedBy { it.offset }
        sorted.zipWithNext().forEach { (a, b) ->
            if (a.offset + a.size > b.offset) throw BuildException("Partitions ${a.name} and ${b.name} overlap")
        }
        sorted.firstOrNull()?.let {
            if (it.offset < TABLE_OFFSET + MAX_SIZE) throw BuildException("Partition ${it.name} overlaps the partition table")
        }
    }
}
