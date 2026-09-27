package org.espsketchide.buildengine.esp32

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.espsketchide.buildengine.BuildException
import org.espsketchide.buildengine.GoldenEnv
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class PartitionTableTest {

    @Test
    fun parsesNamedTypesNumbersAndSuffixes() {
        val parts = PartitionTable.parse(
            """
            # Name, Type, SubType, Offset, Size, Flags
            nvs,      data, nvs,     0x9000,  20K,
            app0,     app,  ota_0,   ,        1M,  readonly
            custom,   0x40, 0x7,     ,        0x1000, encrypted:readonly
            """.trimIndent()
        )

        assertThat(parts[0]).isEqualTo(PartitionTable.Partition("nvs", 0x01, 0x02, 0x9000, 20 * 1024, 0))
        // Blank offsets continue after the previous partition, apps aligned to 64 KiB.
        assertThat(parts[1]).isEqualTo(PartitionTable.Partition("app0", 0x00, 0x10, 0x10000, 1024 * 1024, 0x2))
        assertThat(parts[2]).isEqualTo(PartitionTable.Partition("custom", 0x40, 0x07, 0x110000, 0x1000, 0x3))
    }

    @Test
    fun binaryLayout() {
        val bin = PartitionTable.fromCsv("nvs, data, nvs, 0x9000, 0x5000,")

        assertThat(bin).hasLength(0xC00)
        assertThat(bin.copyOfRange(0, 4).map { it.toInt() and 0xFF }).containsExactly(0xAA, 0x50, 0x01, 0x02).inOrder()
        assertThat(String(bin, 12, 3)).isEqualTo("nvs")
        assertThat(bin[32].toInt() and 0xFF).isEqualTo(0xEB) // MD5 entry follows the last partition
        assertThat(bin.copyOfRange(64, 0xC00).toSet()).containsExactly(0xFF.toByte())
    }

    @Test
    fun rejectsOverlapsBadAlignmentAndLongNames() {
        assertThrows(BuildException::class.java) { PartitionTable.parse("a, data, nvs, 0x9000, 0x5000\nb, data, nvs, 0xa000, 0x1000") }
        assertThrows(BuildException::class.java) { PartitionTable.parse("app0, app, factory, 0x11000, 0x1000") }
        assertThrows(BuildException::class.java) { PartitionTable.parse("a_name_longer_than_16, data, nvs, 0x9000, 0x1000") }
        assertThrows(BuildException::class.java) { PartitionTable.parse("x, data, nvs, 0x8000, 0x1000") }
    }

    /** Every CSV the core ships must produce exactly the same table as its gen_esp32part.py. */
    @Test
    fun matchesGenEsp32partForEveryShippedCsv() {
        val ref = GoldenEnv.require()
        val platformDir = File(GoldenEnv.env().getValue("PLATFORM_DIR"))
        val csvs = File(platformDir, "tools/partitions").listFiles().orEmpty().filter { it.extension == "csv" }.sortedBy { it.name }
        assertThat(csvs.size).isAtLeast(40)
        for (csv in csvs) {
            val expected = File(ref, "partitions/${csv.nameWithoutExtension}.bin")
            if (!expected.isFile) continue // rejected by gen_esp32part too
            assertWithMessage(csv.name).that(PartitionTable.fromCsv(csv.readText())).isEqualTo(expected.readBytes())
        }
    }
}
