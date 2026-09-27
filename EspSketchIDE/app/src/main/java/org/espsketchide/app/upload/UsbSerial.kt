package org.espsketchide.app.upload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.suspendCancellableCoroutine
import org.espsketchide.esptool.SerialLink
import java.io.IOException
import kotlin.coroutines.resume

/** A USB serial adapter the phone can see (CP210x, CH340, FTDI, …). */
class UsbPort(val driver: UsbSerialDriver) {
    val device: UsbDevice get() = driver.device
    val label: String
        get() = listOfNotNull(device.productName, device.manufacturerName).firstOrNull()
            ?: "%04x:%04x".format(device.vendorId, device.productId)
    val chipName: String get() = driver.javaClass.simpleName.removeSuffix("SerialDriver")
}

object UsbSerial {
    private const val ACTION_PERMISSION = "org.espsketchide.app.USB_PERMISSION"

    fun ports(context: Context): List<UsbPort> {
        val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager).map(::UsbPort)
    }

    /** Asks the user for access to [port] if the app doesn't have it yet. */
    suspend fun requestPermission(context: Context, port: UsbPort): Boolean {
        val manager = context.getSystemService(UsbManager::class.java) ?: return false
        if (manager.hasPermission(port.device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    if (cont.isActive) cont.resume(manager.hasPermission(port.device))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            // Explicit (package-scoped) intent; MUTABLE because the system adds the device extras.
            val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(port.device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
    }

    fun open(context: Context, port: UsbPort, baud: Int = 115_200): UsbSerialLink {
        val manager = context.getSystemService(UsbManager::class.java) ?: throw IOException("No USB support")
        val connection = manager.openDevice(port.device) ?: throw IOException("Couldn't open ${port.label}. Unplug it and try again.")
        val serial = port.driver.ports.first()
        try {
            serial.open(connection)
            serial.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: IOException) {
            runCatching { serial.close() }
            throw e
        }
        return UsbSerialLink(serial)
    }
}

/** [SerialLink] over usb-serial-for-android. */
class UsbSerialLink(private val port: UsbSerialPort) : SerialLink {

    // USB reads must be at least one packet; keep extra bytes for the next read.
    private val chunk = ByteArray(maxOf(16 * 1024, port.readEndpoint?.maxPacketSize ?: 64))
    private var pending = ByteArray(0)

    override fun write(data: ByteArray) = port.write(data, WRITE_TIMEOUT_MS)

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (pending.isEmpty()) {
            // 0 means "wait forever" to the driver.
            val n = port.read(chunk, maxOf(1, timeoutMs))
            if (n <= 0) return 0
            pending = chunk.copyOf(n)
        }
        val n = minOf(buffer.size, pending.size)
        pending.copyInto(buffer, 0, 0, n)
        pending = pending.copyOfRange(n, pending.size)
        return n
    }

    override fun setBaudRate(baud: Int) = port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

    override fun setDtr(value: Boolean) { port.dtr = value }

    override fun setRts(value: Boolean) { port.rts = value }

    override fun discardInput() {
        pending = ByteArray(0)
        try {
            port.purgeHwBuffers(true, false)
        } catch (e: UnsupportedOperationException) {
            // Drain what's buffered instead.
            while (port.read(chunk, 20) > 0) { /* drop */ }
        }
    }

    override fun close() {
        runCatching { port.close() }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2_000
    }
}
