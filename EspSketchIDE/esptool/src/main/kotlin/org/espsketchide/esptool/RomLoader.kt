package org.espsketchide.esptool

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reply to a command: the 32-bit `value` field and the payload without its status bytes. */
class Response(val op: Int, val value: Int, val data: ByteArray)

class EspCommandException(val op: Int, val status: Int, val error: Int) :
    Exception("Command 0x%02x failed: status %d, error 0x%02x (%s)".format(op, status, error, describe(error))) {
    companion object {
        fun describe(error: Int) = when (error) {
            0x05 -> "received message is invalid"
            0x06 -> "failed to act on received message"
            0x07 -> "invalid CRC in message"
            0x08 -> "flash write error"
            0x09 -> "flash read error"
            0x0A -> "flash read length error"
            0x0B -> "deflate error"
            else -> "unknown"
        }
    }
}

/**
 * Talks to the ESP32 ROM serial bootloader (not esptool's RAM "stub").
 *
 * Packet: direction 0x00, opcode, 16-bit payload length, 32-bit checksum (data commands only:
 * XOR of the data bytes seeded with 0xEF), payload. Reply: direction 0x01, opcode, length,
 * 32-bit value, payload ending with status bytes (4 on the ESP32 ROM: status, error, 0, 0).
 * All integers little-endian.
 */
class RomLoader(private val link: SerialLink) {

    private val decoder = Slip.Decoder()
    private val pending = ArrayDeque<ByteArray>()
    private val buffer = ByteArray(4096)

    /** Tries SYNC until the ROM answers. The link must already be in download mode. */
    fun sync(attempts: Int = 7) {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        repeat(attempts) {
            link.discardInput()
            pending.clear()
            try {
                command(OP_SYNC, payload, timeoutMs = 100)
                // The ROM answers SYNC several times; drain the extra replies.
                while (receive(100) != null) { /* drain */ }
                pending.clear()
                return
            } catch (e: Exception) {
                // try again
            }
        }
        throw EspProtocolException("No reply to SYNC. Is the board in download mode (hold BOOT, tap EN)?")
    }

    fun readReg(address: Int): Int = command(OP_READ_REG, le(address)).value

    /** Identifies the chip from the ROM's magic register. Only the ESP32 is supported for now. */
    fun detectChip(): Chip {
        val magic = readReg(CHIP_MAGIC_REG)
        return Chip.entries.firstOrNull { magic in it.magic }
            ?: throw EspProtocolException("Unsupported chip (magic 0x%08x)".format(magic))
    }

    /** Selects the default SPI flash pins. The ROM loader takes a second (zero) word. */
    fun spiAttach() = command(OP_SPI_ATTACH, le(0) + le(0))

    fun spiSetParams(totalSize: Int) = command(
        OP_SPI_SET_PARAMS,
        le(0) + le(totalSize) + le(64 * 1024) + le(4 * 1024) + le(256) + le(0xFFFF)
    )

    fun changeBaudRate(newBaud: Int, currentBaud: Int) {
        // The ROM loader expects 0 as the "old baud" argument.
        command(OP_CHANGE_BAUDRATE, le(newBaud) + le(0))
        link.setBaudRate(newBaud)
        Thread.sleep(50)
        link.discardInput()
        pending.clear()
    }

    /** Starts an uncompressed write: the ROM erases [eraseSize] bytes from [offset]. */
    fun flashBegin(eraseSize: Int, blocks: Int, blockSize: Int, offset: Int) =
        command(OP_FLASH_BEGIN, le(eraseSize) + le(blocks) + le(blockSize) + le(offset),
            timeoutMs = eraseTimeoutMs(eraseSize))

    /** One uncompressed block; must be exactly the block size given to [flashBegin]. */
    fun flashData(block: ByteArray, sequence: Int) = command(
        OP_FLASH_DATA,
        le(block.size) + le(sequence) + le(0) + le(0) + block,
        checksum = checksum(block),
        timeoutMs = 10_000
    )

    /** Ends an uncompressed write. [reboot] = false keeps the chip in the loader. */
    fun flashEnd(reboot: Boolean) = command(OP_FLASH_END, le(if (reboot) 0 else 1))

    /**
     * Starts a compressed write. The ROM erases [eraseSize] bytes from [offset] and then takes
     * [blocks] compressed blocks.
     */
    fun flashDeflBegin(eraseSize: Int, blocks: Int, blockSize: Int, offset: Int) =
        command(OP_FLASH_DEFL_BEGIN, le(eraseSize) + le(blocks) + le(blockSize) + le(offset),
            timeoutMs = eraseTimeoutMs(eraseSize))

    fun flashDeflData(block: ByteArray, sequence: Int) = command(
        OP_FLASH_DEFL_DATA,
        le(block.size) + le(sequence) + le(0) + le(0) + block,
        checksum = checksum(block),
        timeoutMs = 10_000
    )

    /** Ends the compressed write. [reboot] = false keeps the chip in the loader. */
    fun flashDeflEnd(reboot: Boolean) = command(OP_FLASH_DEFL_END, le(if (reboot) 0 else 1))

    /** MD5 of a flash region, computed by the chip. The ROM replies with 32 hex characters. */
    fun flashMd5(offset: Int, size: Int): String {
        val reply = command(OP_SPI_FLASH_MD5, le(offset) + le(size) + le(0) + le(0),
            timeoutMs = 5_000 + size / 1024 * 8)
        return String(reply.data.copyOf(32), Charsets.US_ASCII).lowercase()
    }

    fun command(op: Int, payload: ByteArray, checksum: Int = 0, timeoutMs: Int = 3_000): Response {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x00).put(op.toByte()).putShort(payload.size.toShort()).putInt(checksum).array()
        link.write(Slip.encode(header + payload))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) throw EspProtocolException("Timed out waiting for reply to 0x%02x".format(op))
            val packet = receive(remaining) ?: continue
            if (packet.size < 8 || packet[0] != 0x01.toByte() || (packet[1].toInt() and 0xFF) != op) continue
            val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            val size = bb.getShort(2).toInt() and 0xFFFF
            val value = bb.getInt(4)
            val data = packet.copyOfRange(8, minOf(packet.size, 8 + size))
            if (data.size < STATUS_BYTES) throw EspProtocolException("Short reply to 0x%02x".format(op))
            val status = data[data.size - STATUS_BYTES].toInt() and 0xFF
            val error = data[data.size - STATUS_BYTES + 1].toInt() and 0xFF
            if (status != 0) throw EspCommandException(op, status, error)
            return Response(op, value, data.copyOf(data.size - STATUS_BYTES))
        }
    }

    private fun receive(timeoutMs: Int): ByteArray? {
        pending.removeFirstOrNull()?.let { return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = link.read(buffer, (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1))
            if (n > 0) {
                pending.addAll(decoder.feed(buffer, n))
                pending.removeFirstOrNull()?.let { return it }
            }
        }
        return null
    }

    enum class Chip(val displayName: String, vararg val magic: Int) {
        ESP32("ESP32", 0x00F01D83),
    }

    companion object {
        const val OP_FLASH_BEGIN = 0x02
        const val OP_FLASH_DATA = 0x03
        const val OP_FLASH_END = 0x04
        const val OP_SYNC = 0x08
        const val OP_WRITE_REG = 0x09
        const val OP_READ_REG = 0x0A
        const val OP_SPI_SET_PARAMS = 0x0B
        const val OP_SPI_ATTACH = 0x0D
        const val OP_CHANGE_BAUDRATE = 0x0F
        const val OP_FLASH_DEFL_BEGIN = 0x10
        const val OP_FLASH_DEFL_DATA = 0x11
        const val OP_FLASH_DEFL_END = 0x12
        const val OP_SPI_FLASH_MD5 = 0x13

        const val CHIP_MAGIC_REG = 0x40001000
        const val STATUS_BYTES = 4 // ESP32 ROM

        fun checksum(data: ByteArray): Int = data.fold(0xEF) { acc, b -> acc xor (b.toInt() and 0xFF) }

        fun le(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

        /** Erasing takes roughly up to 10 s per MB on real chips. */
        private fun eraseTimeoutMs(bytes: Int) = maxOf(3_000, (10_000L * bytes / (1024 * 1024)).toInt() + 3_000)
    }
}
