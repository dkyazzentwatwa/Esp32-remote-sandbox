package org.espsketchide.app.upload

import com.google.common.truth.Truth.assertThat
import org.espsketchide.esptool.SerialLink
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SerialMonitorTest {

    private class QueueLink : SerialLink {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val written = mutableListOf<String>()
        var closed = false
        override fun write(data: ByteArray) { written += String(data) }
        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            val next = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            next.copyInto(buffer)
            return next.size
        }
        override fun setBaudRate(baud: Int) = Unit
        override fun setDtr(value: Boolean) = Unit
        override fun setRts(value: Boolean) = Unit
        override fun discardInput() = Unit
        override fun close() { closed = true }
    }

    private fun awaitText(m: SerialMonitor, expected: String) {
        val end = System.currentTimeMillis() + 3_000
        while (m.text.value != expected && System.currentTimeMillis() < end) Thread.sleep(10)
        assertThat(m.text.value).isEqualTo(expected)
    }

    @Test
    fun decodesUtf8AcrossReadsAndNormalisesLineEndings() {
        val link = QueueLink()
        val m = SerialMonitor(link).start()
        val degree = "°C".toByteArray() // 0xC2 0xB0 'C'

        link.incoming.put("Temp: 21".toByteArray() + degree.copyOfRange(0, 1))
        link.incoming.put(degree.copyOfRange(1, 3) + "\r\nok\r\n".toByteArray())

        awaitText(m, "Temp: 21°C\nok\n")
        m.close()
        assertThat(link.closed).isTrue()
    }

    @Test
    fun keepsOnlyTheTailAndSendsLines() {
        val link = QueueLink()
        val m = SerialMonitor(link, maxChars = 5).start()

        link.incoming.put("0123456789".toByteArray())
        awaitText(m, "56789")
        m.send("hello")
        m.clear()

        assertThat(m.text.value).isEmpty()
        assertThat(link.written).containsExactly("hello\n")
        m.close()
    }
}
