package org.espsketchide.app.upload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.espsketchide.esptool.SerialLink
import java.nio.charset.CodingErrorAction

/**
 * Reads a board's serial output on a background thread and keeps the last [maxChars] characters
 * of it in [text]. Bytes are decoded as UTF-8, with invalid bytes shown as �, as the Arduino IDE does.
 */
class SerialMonitor(private val link: SerialLink, private val maxChars: Int = 64 * 1024) : AutoCloseable {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Set when reading fails, e.g. the cable was unplugged. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val bytes = java.nio.ByteBuffer.allocate(8192)
    private val chars = java.nio.CharBuffer.allocate(8192)
    private val buffer = StringBuilder()

    @Volatile
    private var running = true

    private val reader = Thread({
        val chunk = ByteArray(4096)
        try {
            while (running) {
                val n = link.read(chunk, 200)
                if (n > 0) append(chunk, n)
            }
        } catch (e: Exception) {
            if (running) _error.value = e.message ?: "Serial connection lost"
        }
    }, "serial-monitor").apply { isDaemon = true }

    fun start(): SerialMonitor = apply { reader.start() }

    fun send(line: String, ending: String = "\n") = link.write((line + ending).toByteArray())

    fun setBaudRate(baud: Int) = link.setBaudRate(baud)

    /** Restarts the board (EN pulse) so its output starts from the beginning. */
    fun resetBoard() = org.espsketchide.esptool.AutoReset.hardReset(link)

    fun clear() = synchronized(buffer) {
        buffer.setLength(0)
        _text.value = ""
    }

    private fun append(chunk: ByteArray, n: Int) = synchronized(buffer) {
        var offset = 0
        while (offset < n) {
            val take = minOf(bytes.remaining(), n - offset)
            bytes.put(chunk, offset, take)
            offset += take
            bytes.flip()
            decoder.decode(bytes, chars, false)
            bytes.compact()
            chars.flip()
            buffer.append(chars)
            chars.clear()
        }
        // \r\n -> \n; a lone \r (progress-bar style output) is dropped.
        var i = buffer.indexOf("\r")
        while (i >= 0) {
            buffer.deleteCharAt(i)
            i = buffer.indexOf("\r", i)
        }
        if (buffer.length > maxChars) buffer.delete(0, buffer.length - maxChars)
        _text.value = buffer.toString()
    }

    override fun close() {
        running = false
        reader.join(1_000)
        link.close()
    }
}
