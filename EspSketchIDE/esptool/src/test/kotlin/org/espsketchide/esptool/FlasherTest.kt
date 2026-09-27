package org.espsketchide.esptool

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class FlasherTest {

    private val random = Random(42)
    private fun blob(size: Int) = ByteArray(size).also { bytes ->
        // Half random, half repetitive, so compression is exercised both ways.
        for (i in bytes.indices) bytes[i] = if (i % 2048 < 1024) random.nextInt().toByte() else (i / 7).toByte()
    }

    @Test
    fun writesEveryImageAtItsOffset() {
        val rom = FakeRom()
        val bootloader = blob(24_000)
        val app = blob(250_000)
        val progress = mutableListOf<FlashProgress>()

        Flasher(rom).flash(listOf(FlashImage(0x1000, bootloader, "bootloader"), FlashImage(0x10000, app, "app")), progress = progress::add)

        assertThat(rom.flash.copyOfRange(0x1000, 0x1000 + bootloader.size)).isEqualTo(bootloader)
        assertThat(rom.flash.copyOfRange(0x10000, 0x10000 + app.size)).isEqualTo(app)
        assertThat(rom.flash[0x0FFF]).isEqualTo(0xFF.toByte()) // untouched before the first image
        assertThat(progress.first()).isEqualTo(FlashProgress.Connecting)
        assertThat(progress).contains(FlashProgress.Connected(RomLoader.Chip.ESP32))
        assertThat(progress).contains(FlashProgress.Verified("app"))
        assertThat(progress.last()).isEqualTo(FlashProgress.Done)
    }

    @Test
    fun compressedModeWritesTheSameBytes() {
        val rom = FakeRom()
        val app = blob(100_000)

        Flasher(rom).flash(listOf(FlashImage(0x10000, app)), compress = true)

        assertThat(rom.flash.copyOfRange(0x10000, 0x10000 + app.size)).isEqualTo(app)
        assertThat(rom.ops).contains(RomLoader.OP_FLASH_DEFL_DATA)
        assertThat(rom.ops).doesNotContain(RomLoader.OP_FLASH_DATA)
    }

    @Test
    fun lastBlockIsPaddedWithErasedBytes() {
        val rom = FakeRom()
        rom.flash.fill(0x00) // pretend old firmware was there

        Flasher(rom).flash(listOf(FlashImage(0x8000, blob(3000))))

        assertThat(rom.flash.copyOfRange(0x8000 + 3000, 0x9000).toSet()).containsExactly(0xFF.toByte())
        assertThat(rom.flash[0x9000]).isEqualTo(0x00.toByte()) // next sector untouched
    }

    @Test
    fun oddSizedImagesArePaddedToFourBytes() {
        val rom = FakeRom()

        Flasher(rom).flash(listOf(FlashImage(0, blob(1001))))

        assertThat(Flasher.padTo(ByteArray(5), 4)).hasLength(8)
        assertThat(rom.flash.copyOfRange(1001, 1004).toSet()).containsExactly(0xFF.toByte())
    }

    @Test
    fun followsRomLoaderCommandOrder() {
        val rom = FakeRom()

        Flasher(rom).flash(listOf(FlashImage(0x10000, blob(5000))))

        val distinct = rom.ops.fold(mutableListOf<Int>()) { acc, op -> if (acc.lastOrNull() != op) acc += op; acc }
        assertThat(distinct).containsExactly(
            RomLoader.OP_SYNC, RomLoader.OP_READ_REG, RomLoader.OP_SPI_ATTACH, RomLoader.OP_SPI_SET_PARAMS,
            RomLoader.OP_FLASH_BEGIN, RomLoader.OP_FLASH_DATA, RomLoader.OP_SPI_FLASH_MD5
        ).inOrder()
    }

    @Test
    fun retriesSyncUntilTheRomAnswers() {
        val rom = FakeRom().apply { dropSyncs = 3 }

        Flasher(rom).flash(listOf(FlashImage(0, blob(100))))

        assertThat(rom.ops.count { it == RomLoader.OP_SYNC }).isAtLeast(4)
    }

    @Test
    fun noReplyToSyncExplainsDownloadMode() {
        val rom = FakeRom().apply { dropSyncs = 100 }

        val e = assertThrows(EspProtocolException::class.java) { Flasher(rom).flash(listOf(FlashImage(0, blob(100)))) }
        assertThat(e).hasMessageThat().contains("download mode")
    }

    @Test
    fun rejectsOtherChips() {
        val rom = FakeRom(magic = 0x12345678)

        val e = assertThrows(EspProtocolException::class.java) { Flasher(rom).flash(listOf(FlashImage(0, blob(100)))) }
        assertThat(e).hasMessageThat().contains("Unsupported chip")
    }

    @Test
    fun romErrorsSurfaceWithTheirMeaning() {
        val rom = FakeRom().apply { corruptNextDataChecksum = true }

        val e = assertThrows(EspCommandException::class.java) { Flasher(rom).flash(listOf(FlashImage(0, blob(3000)))) }
        assertThat(e.error).isEqualTo(0x07)
        assertThat(e).hasMessageThat().contains("invalid CRC")
    }

    @Test
    fun refusesImagesThatDoNotFit() {
        assertThrows(IllegalArgumentException::class.java) {
            Flasher(FakeRom()).flash(listOf(FlashImage(4 * 1024 * 1024 - 10, blob(100))))
        }
    }

    @Test
    fun checksumMatchesDocumentedSeed() {
        assertThat(RomLoader.checksum(ByteArray(0))).isEqualTo(0xEF)
        assertThat(RomLoader.checksum(byteArrayOf(0xEF.toByte()))).isEqualTo(0)
        assertThat(RomLoader.checksum(byteArrayOf(1, 2, 4))).isEqualTo(0xEF xor 7)
    }
}
