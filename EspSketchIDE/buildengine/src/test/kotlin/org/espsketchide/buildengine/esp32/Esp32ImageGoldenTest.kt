package org.espsketchide.buildengine.esp32

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.espsketchide.buildengine.GoldenEnv
import org.junit.Test
import java.io.File

/**
 * elf2image and merge-bin must produce exactly what esptool produced in arduino-cli's builds of
 * the bundled examples (app .bin, bootloader .bin, merged flash image).
 */
class Esp32ImageGoldenTest {

    private val examples = listOf("Blink", "HelloSerial", "AnalogRead", "Button", "Fade", "WiFiScan", "WiFiAccessPointLed", "BLEScan")
    private val appOptions = Esp32Image.Options(flashMode = "dio", flashFreq = "80m", flashSize = "4MB", elfSha256Offset = 0xB0)

    @Test
    fun appImagesMatchEsptool() {
        val ref = GoldenEnv.require()
        for (name in examples) {
            val build = File(ref, "builds/$name")
            val ours = Esp32Image.elf2image(File(build, "$name.ino.elf").readBytes(), appOptions)
            assertWithMessage("$name.ino.bin").that(ours).isEqualTo(File(build, "$name.ino.bin").readBytes())
        }
    }

    @Test
    fun bootloaderImageMatchesEsptool() {
        val ref = GoldenEnv.require()
        val sdk = File(GoldenEnv.env().getValue("ARDUINO15"), "packages/esp32/tools/esp32-libs").listFiles()!!.single()
        val elf = File(sdk, "bin/bootloader_qio_80m.elf")
        val ours = Esp32Image.elf2image(elf.readBytes(), Esp32Image.Options("dio", "80m", "4MB"))

        assertThat(ours).isEqualTo(File(ref, "builds/Blink/Blink.ino.bootloader.bin").readBytes())
    }

    @Test
    fun mergedFlashImageMatchesEsptool() {
        val ref = GoldenEnv.require()
        val platformDir = File(GoldenEnv.env().getValue("PLATFORM_DIR"))
        for (name in examples) {
            val build = File(ref, "builds/$name")
            val merged = Esp32Image.mergeBin(
                listOf(
                    0x1000 to File(build, "$name.ino.bootloader.bin").readBytes(),
                    0x8000 to File(build, "$name.ino.partitions.bin").readBytes(),
                    0xE000 to File(platformDir, "tools/partitions/boot_app0.bin").readBytes(),
                    0x10000 to File(build, "$name.ino.bin").readBytes(),
                ),
                padToSize = 4 * 1024 * 1024,
            )
            assertWithMessage("$name.ino.merged.bin").that(merged).isEqualTo(File(build, "$name.ino.merged.bin").readBytes())
        }
    }
}
