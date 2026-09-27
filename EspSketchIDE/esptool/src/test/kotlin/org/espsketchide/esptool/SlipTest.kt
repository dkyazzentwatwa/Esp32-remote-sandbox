package org.espsketchide.esptool

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SlipTest {

    @Test
    fun escapesEndAndEscBytes() {
        val encoded = Slip.encode(byteArrayOf(0x01, 0xC0.toByte(), 0x02, 0xDB.toByte()))

        assertThat(encoded.map { it.toInt() and 0xFF })
            .containsExactly(0xC0, 0x01, 0xDB, 0xDC, 0x02, 0xDB, 0xDD, 0xC0).inOrder()
    }

    @Test
    fun roundTripsEveryByteValue() {
        val packet = ByteArray(256) { it.toByte() }

        val decoded = Slip.Decoder().feed(Slip.encode(packet))

        assertThat(decoded).hasSize(1)
        assertThat(decoded[0]).isEqualTo(packet)
    }

    @Test
    fun decodesAcrossSplitReadsAndIgnoresNoise() {
        val decoder = Slip.Decoder()
        val stream = "boot:0x3\r\n".toByteArray() + Slip.encode(byteArrayOf(1, 2, 3)) + Slip.encode(byteArrayOf(4, 0xC0.toByte()))

        val packets = stream.toList().chunked(3).flatMap { decoder.feed(it.toByteArray()) }

        assertThat(packets.map { it.toList() }).containsExactly(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 0xC0.toByte())).inOrder()
        assertThat(decoder.noise.toString()).contains("boot:0x3")
    }

    @Test
    fun rejectsInvalidEscape() {
        assertThrows(EspProtocolException::class.java) {
            Slip.Decoder().feed(byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x00, 0xC0.toByte()))
        }
    }
}
