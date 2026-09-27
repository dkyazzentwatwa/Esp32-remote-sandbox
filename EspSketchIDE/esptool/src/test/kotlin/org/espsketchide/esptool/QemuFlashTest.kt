package org.espsketchide.esptool

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket

/**
 * Flashes Espressif's emulated ESP32 ROM (their QEMU fork, in download mode) and compares the
 * resulting flash image byte-for-byte with one written by Espressif's esptool in ROM mode.
 *
 * Skipped unless these are set:
 *  - ESP_QEMU: path to qemu-system-xtensa (Espressif build)
 *  - ESP_TEST_IMAGES: "offset=file,offset=file,..." (e.g. 0x1000=Blink.ino.bootloader.bin,...)
 *  - ESP_FLASH_REFERENCE: 4 MB flash image produced by `esptool --no-stub write-flash` of those images
 * Optional: ESP_FLASH_OUT keeps our resulting flash image for inspection.
 */
class QemuFlashTest {

    @Test
    fun flashImageMatchesEsptool() {
        val qemu = System.getenv("ESP_QEMU")
        val images = System.getenv("ESP_TEST_IMAGES")
        val reference = System.getenv("ESP_FLASH_REFERENCE")
        assumeTrue("QEMU test not configured", qemu != null && images != null && reference != null)

        val flashFile = File.createTempFile("esp32-flash", ".bin").apply { deleteOnExit() }
        flashFile.writeBytes(ByteArray(4 * 1024 * 1024))
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(
            qemu!!, "-display", "none", "-machine", "esp32",
            "-drive", "file=${flashFile.path},if=mtd,format=raw",
            "-global", "driver=esp32.gpio,property=strap_mode,value=0x0f", // boot into download mode
            "-serial", "tcp::$port,server,nowait", "-monitor", "none"
        ).redirectErrorStream(true).redirectOutput(File.createTempFile("qemu", ".log")).start()
        try {
            val link = connect(port)
            val parsed = images!!.split(',').map { entry ->
                val (offset, path) = entry.split('=', limit = 2)
                FlashImage(Integer.decode(offset), File(path).readBytes(), File(path).name)
            }
            val started = System.nanoTime()
            link.use { Flasher(it).flash(parsed) }
            println("QEMU flash of ${parsed.sumOf { it.data.size }} bytes took ${(System.nanoTime() - started) / 1_000_000} ms")
        } finally {
            process.destroy()
            process.waitFor()
        }

        System.getenv("ESP_FLASH_OUT")?.let { flashFile.copyTo(File(it), overwrite = true) }
        assertThat(flashFile.readBytes()).isEqualTo(File(reference!!).readBytes())
    }

    private fun connect(port: Int): TcpSerialLink {
        repeat(50) {
            try { return TcpSerialLink("127.0.0.1", port) } catch (e: Exception) { Thread.sleep(100) }
        }
        error("QEMU serial port $port did not open")
    }
}
