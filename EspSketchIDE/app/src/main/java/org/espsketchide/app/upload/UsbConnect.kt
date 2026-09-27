package org.espsketchide.app.upload

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.espsketchide.app.R
import kotlin.coroutines.resume

/** The screen side of connecting to a board: pick the USB device, get permission, open it. */
object UsbConnect {

    /** Returns an open link, or null after telling the user why not through [onError]. */
    suspend fun pickAndOpen(activity: Activity, baud: Int, onError: (String) -> Unit): UsbSerialLink? {
        val ports = withContext(Dispatchers.IO) { UsbSerial.ports(activity) }
        val port = when (ports.size) {
            0 -> { onError(activity.getString(R.string.error_no_usb)); return null }
            1 -> ports.single()
            else -> choose(activity, ports) ?: return null
        }
        if (!UsbSerial.requestPermission(activity, port)) {
            onError(activity.getString(R.string.error_usb_permission))
            return null
        }
        return try {
            withContext(Dispatchers.IO) { UsbSerial.open(activity, port, baud) }
        } catch (e: Exception) {
            onError(activity.getString(R.string.error_usb_open, e.message ?: e.javaClass.simpleName))
            null
        }
    }

    private suspend fun choose(activity: Activity, ports: List<UsbPort>): UsbPort? = suspendCancellableCoroutine { cont ->
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_usb_title)
            .setItems(ports.map { "${it.label} (${it.chipName})" }.toTypedArray()) { _, which ->
                if (cont.isActive) cont.resume(ports[which])
            }
            .setOnCancelListener { if (cont.isActive) cont.resume(null) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }
}
