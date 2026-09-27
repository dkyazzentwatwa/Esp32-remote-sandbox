package org.espsketchide.esptool

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A byte pipe to the chip plus the two modem lines the ESP auto-reset circuit uses.
 * The app implements it over usb-serial-for-android; tests use a TCP socket (QEMU) or fakes.
 */
interface SerialLink : AutoCloseable {
    fun write(data: ByteArray)

    /** Reads up to [buffer].size bytes, waiting at most [timeoutMs]. Returns 0 on timeout. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    fun setBaudRate(baud: Int)

    fun setDtr(value: Boolean)

    fun setRts(value: Boolean)

    /** Drops anything already received. */
    fun discardInput()
}

/** A serial port exposed over TCP, e.g. QEMU's `-serial tcp::5555,server`. No modem lines. */
class TcpSerialLink(host: String, port: Int, connectTimeoutMs: Int = 5000) : SerialLink {
    private val socket = Socket().apply { connect(InetSocketAddress(host, port), connectTimeoutMs); tcpNoDelay = true }
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        return try {
            val n = input.read(buffer)
            if (n < 0) throw IOException("Connection closed") else n
        } catch (e: SocketTimeoutException) {
            0
        }
    }

    override fun setBaudRate(baud: Int) = Unit
    override fun setDtr(value: Boolean) = Unit
    override fun setRts(value: Boolean) = Unit

    override fun discardInput() {
        val buffer = ByteArray(4096)
        while (read(buffer, 20) > 0) { /* drain */ }
    }

    override fun close() = socket.close()
}
