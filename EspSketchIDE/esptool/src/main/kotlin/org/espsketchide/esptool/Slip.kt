package org.espsketchide.esptool

import java.io.ByteArrayOutputStream

/**
 * SLIP framing (RFC 1055) as used by the ESP ROM bootloader: each packet is wrapped in 0xC0,
 * with 0xC0 sent as DB DC and 0xDB sent as DB DD.
 */
object Slip {
    const val END = 0xC0.toByte()
    const val ESC = 0xDB.toByte()
    private const val ESC_END = 0xDC.toByte()
    private const val ESC_ESC = 0xDD.toByte()

    fun encode(packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(packet.size + 8)
        out.write(END.toInt())
        for (b in packet) {
            when (b) {
                END -> { out.write(ESC.toInt()); out.write(ESC_END.toInt()) }
                ESC -> { out.write(ESC.toInt()); out.write(ESC_ESC.toInt()) }
                else -> out.write(b.toInt())
            }
        }
        out.write(END.toInt())
        return out.toByteArray()
    }

    /** Incremental decoder: feed bytes as they arrive, collect complete packets. */
    class Decoder {
        private val current = ByteArrayOutputStream()
        private var inPacket = false
        private var escaped = false

        /** Bytes seen outside any packet (e.g. boot messages); kept for diagnostics. */
        val noise = ByteArrayOutputStream()

        fun feed(data: ByteArray, length: Int = data.size): List<ByteArray> {
            val packets = mutableListOf<ByteArray>()
            for (i in 0 until length) {
                val b = data[i]
                if (!inPacket) {
                    if (b == END) { inPacket = true; current.reset() } else noise.write(b.toInt())
                    continue
                }
                if (escaped) {
                    escaped = false
                    when (b) {
                        ESC_END -> current.write(END.toInt())
                        ESC_ESC -> current.write(ESC.toInt())
                        else -> throw EspProtocolException("Invalid SLIP escape 0x%02x".format(b))
                    }
                    continue
                }
                when (b) {
                    ESC -> escaped = true
                    END -> {
                        // Back-to-back 0xC0 (end of one packet, start of next) gives an empty
                        // packet; treat it as a new start instead.
                        if (current.size() > 0) {
                            packets += current.toByteArray()
                            inPacket = false
                        }
                        current.reset()
                    }
                    else -> current.write(b.toInt())
                }
            }
            return packets
        }
    }
}

class EspProtocolException(message: String) : Exception(message)
