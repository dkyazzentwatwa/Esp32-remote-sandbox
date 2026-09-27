package org.espsketchide.esptool

import java.security.MessageDigest
import java.util.zip.Deflater

/** One file to write, e.g. the bootloader at 0x1000 or the app at 0x10000. */
class FlashImage(val offset: Int, val data: ByteArray, val name: String = "0x%x".format(offset))

/** Progress of a flash operation, for the UI. */
sealed interface FlashProgress {
    data object Connecting : FlashProgress
    data class Connected(val chip: RomLoader.Chip) : FlashProgress
    data class Writing(val image: String, val bytesDone: Int, val bytesTotal: Int) : FlashProgress
    data class Verified(val image: String) : FlashProgress
    data object Done : FlashProgress
}

/**
 * Writes images to flash through the ESP32 ROM loader and verifies each with the chip's MD5.
 * The caller puts the chip into download mode first (see [AutoReset]).
 *
 * Uncompressed by default, which is what esptool does with the ROM loader (`--no-stub`); with
 * Espressif's emulated ROM the result is byte-identical to esptool's. [compress] sends zlib
 * data with FLASH_DEFL_* instead (fewer bytes over the wire); keep it off until it has been
 * checked on real chips: on the emulated ROM it leaves the bytes after each image unerased.
 */
class Flasher(private val link: SerialLink) {

    private val rom = RomLoader(link)

    fun flash(
        images: List<FlashImage>,
        flashSize: Int = 4 * 1024 * 1024,
        baud: Int? = null,
        currentBaud: Int = 115_200,
        compress: Boolean = false,
        progress: (FlashProgress) -> Unit = {}
    ) {
        require(images.isNotEmpty()) { "Nothing to flash" }
        images.forEach { require(it.offset >= 0 && it.offset + it.data.size <= flashSize) { "${it.name} does not fit in flash" } }

        progress(FlashProgress.Connecting)
        rom.sync()
        val chip = rom.detectChip()
        progress(FlashProgress.Connected(chip))
        if (baud != null && baud != currentBaud) rom.changeBaudRate(baud, currentBaud)
        rom.spiAttach()
        rom.spiSetParams(flashSize)

        for (image in images) {
            // esptool pads images to a multiple of 4 bytes with 0xFF.
            val data = padTo(image.data, 4)
            if (compress) writeCompressed(image.name, image.offset, data, progress)
            else writeUncompressed(image.name, image.offset, data, progress)
            val expected = md5Hex(data)
            val actual = rom.flashMd5(image.offset, data.size)
            if (actual != expected) {
                throw EspProtocolException("Verify failed for ${image.name}: chip has $actual, expected $expected")
            }
            progress(FlashProgress.Verified(image.name))
        }
        // No FLASH_END: in the ROM loader it exits the loader to run user code (esptool skips it
        // too). The caller resets the chip into the new firmware with AutoReset.hardReset.
        progress(FlashProgress.Done)
    }

    private fun writeUncompressed(name: String, offset: Int, data: ByteArray, progress: (FlashProgress) -> Unit) {
        val blocks = (data.size + BLOCK_SIZE - 1) / BLOCK_SIZE
        rom.flashBegin(data.size, blocks, BLOCK_SIZE, offset)
        for (seq in 0 until blocks) {
            val from = seq * BLOCK_SIZE
            // The last block is padded with 0xFF (erased flash) to the full block size.
            val block = ByteArray(BLOCK_SIZE) { 0xFF.toByte() }
            data.copyInto(block, 0, from, minOf(data.size, from + BLOCK_SIZE))
            rom.flashData(block, seq)
            progress(FlashProgress.Writing(name, minOf(data.size, from + BLOCK_SIZE), data.size))
        }
    }

    private fun writeCompressed(name: String, offset: Int, data: ByteArray, progress: (FlashProgress) -> Unit) {
        val compressed = deflate(data)
        val blocks = (compressed.size + BLOCK_SIZE - 1) / BLOCK_SIZE
        val eraseSize = (data.size + BLOCK_SIZE - 1) / BLOCK_SIZE * BLOCK_SIZE
        rom.flashDeflBegin(eraseSize, blocks, BLOCK_SIZE, offset)
        for (seq in 0 until blocks) {
            val block = compressed.copyOfRange(seq * BLOCK_SIZE, minOf(compressed.size, (seq + 1) * BLOCK_SIZE))
            rom.flashDeflData(block, seq)
            progress(FlashProgress.Writing(name, minOf(data.size, ((seq + 1).toLong() * data.size / blocks).toInt()), data.size))
        }
    }

    companion object {
        /** Bytes per FLASH_DATA / FLASH_DEFL_DATA packet for the ROM loader. */
        const val BLOCK_SIZE = 0x400

        fun padTo(data: ByteArray, alignment: Int): ByteArray {
            val padded = (data.size + alignment - 1) / alignment * alignment
            return if (padded == data.size) data else data.copyOf(padded).also { it.fill(0xFF.toByte(), data.size, padded) }
        }

        fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION) // zlib format, as the ROM expects
            deflater.setInput(data)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            deflater.end()
            return out.toByteArray()
        }

        fun md5Hex(data: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The DTR/RTS sequence that dev boards' auto-reset circuits (two transistors on EN and GPIO0)
 * turn into "reset into download mode" and "reset and run".
 */
object AutoReset {
    /** EN low, then release it while GPIO0 is held low, so the chip boots into the ROM loader. */
    fun enterDownloadMode(link: SerialLink, holdMs: Long = 100, releaseMs: Long = 50) {
        link.setDtr(false); link.setRts(true)   // EN low (reset)
        Thread.sleep(holdMs)
        link.setDtr(true); link.setRts(false)   // EN high, GPIO0 low
        Thread.sleep(releaseMs)
        link.setDtr(false)                      // release GPIO0
    }

    /** Pulses EN so the chip restarts and runs the new firmware. */
    fun hardReset(link: SerialLink) {
        link.setDtr(false)
        link.setRts(true)
        Thread.sleep(100)
        link.setRts(false)
    }
}
