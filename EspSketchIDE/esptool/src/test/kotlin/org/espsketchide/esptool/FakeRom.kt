package org.espsketchide.esptool

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Our own model of the ESP32 ROM loader, as a [SerialLink], for unit tests. It follows the
 * documented protocol, keeps a flash array, and records the commands it saw. The authoritative
 * check is QemuFlashTest against Espressif's emulated ROM.
 */
class FakeRom(flashSize: Int = 4 * 1024 * 1024, private val magic: Int = 0x00F01D83) : SerialLink {

    val flash = ByteArray(flashSize) { 0xFF.toByte() }
    val ops = mutableListOf<Int>()
    var dropSyncs = 0
    var corruptNextDataChecksum = false

    private val decoder = Slip.Decoder()
    private val outbox = ArrayDeque<Byte>()
    private var writeOffset = 0
    private var inflater: Inflater? = null
    private var expectedSeq = 0

    override fun write(data: ByteArray) {
        decoder.feed(data).forEach(::handle)
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buffer.size && outbox.isNotEmpty()) buffer[n++] = outbox.removeFirst()
        return n
    }

    override fun setBaudRate(baud: Int) = Unit
    override fun setDtr(value: Boolean) = Unit
    override fun setRts(value: Boolean) = Unit
    override fun discardInput() = outbox.clear()
    override fun close() = Unit

    private fun handle(packet: ByteArray) {
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        check(packet[0].toInt() == 0) { "not a request" }
        val op = packet[1].toInt() and 0xFF
        val size = bb.getShort(2).toInt() and 0xFFFF
        val checksum = bb.getInt(4)
        val data = ByteBuffer.wrap(packet, 8, size).slice().order(ByteOrder.LITTLE_ENDIAN)
        ops += op
        when (op) {
            RomLoader.OP_SYNC -> {
                if (dropSyncs > 0) { dropSyncs--; return }
                repeat(3) { reply(op) }
            }
            RomLoader.OP_READ_REG -> reply(op, value = if (data.getInt(0) == RomLoader.CHIP_MAGIC_REG) magic else 0)
            RomLoader.OP_SPI_ATTACH, RomLoader.OP_SPI_SET_PARAMS, RomLoader.OP_CHANGE_BAUDRATE -> reply(op)
            RomLoader.OP_FLASH_BEGIN -> {
                val eraseSize = data.getInt(0)
                writeOffset = data.getInt(12)
                // Real flash erases whole 4 KiB sectors.
                val end = (writeOffset + eraseSize + 0xFFF) / 0x1000 * 0x1000
                flash.fill(0xFF.toByte(), writeOffset, end)
                expectedSeq = 0
                reply(op)
            }
            RomLoader.OP_FLASH_DATA -> {
                val length = data.getInt(0)
                val seq = data.getInt(4)
                val block = ByteArray(length).also { data.position(16); data.get(it) }
                if (corruptNextDataChecksum || checksum != RomLoader.checksum(block)) {
                    corruptNextDataChecksum = false
                    return reply(op, status = 1, error = 0x07)
                }
                if (seq != expectedSeq++) return reply(op, status = 1, error = 0x06)
                System.arraycopy(block, 0, flash, writeOffset, length)
                writeOffset += length
                reply(op)
            }
            RomLoader.OP_FLASH_END -> reply(op)
            RomLoader.OP_FLASH_DEFL_BEGIN -> {
                val eraseSize = data.getInt(0)
                writeOffset = data.getInt(12)
                flash.fill(0xFF.toByte(), writeOffset, writeOffset + eraseSize)
                inflater = Inflater()
                expectedSeq = 0
                reply(op)
            }
            RomLoader.OP_FLASH_DEFL_DATA -> {
                val length = data.getInt(0)
                val seq = data.getInt(4)
                val block = ByteArray(length).also { data.position(16); data.get(it) }
                if (corruptNextDataChecksum || checksum != RomLoader.checksum(block)) {
                    corruptNextDataChecksum = false
                    return reply(op, status = 1, error = 0x07)
                }
                if (seq != expectedSeq++) return reply(op, status = 1, error = 0x06)
                val inf = inflater!!
                inf.setInput(block)
                val out = ByteArray(64 * 1024)
                while (true) {
                    val n = inf.inflate(out)
                    if (n == 0) break
                    System.arraycopy(out, 0, flash, writeOffset, n)
                    writeOffset += n
                }
                reply(op)
            }
            RomLoader.OP_FLASH_DEFL_END -> reply(op)
            RomLoader.OP_SPI_FLASH_MD5 -> {
                val offset = data.getInt(0)
                val length = data.getInt(4)
                val md5 = MessageDigest.getInstance("MD5").digest(flash.copyOfRange(offset, offset + length))
                reply(op, payload = md5.joinToString("") { "%02x".format(it) }.toByteArray())
            }
            else -> reply(op, status = 1, error = 0x05)
        }
    }

    private fun reply(op: Int, value: Int = 0, payload: ByteArray = ByteArray(0), status: Int = 0, error: Int = 0) {
        val body = payload + byteArrayOf(status.toByte(), error.toByte(), 0, 0)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x01).put(op.toByte()).putShort(body.size.toShort()).putInt(value).array()
        Slip.encode(header + body).forEach { outbox.addLast(it) }
    }
}
