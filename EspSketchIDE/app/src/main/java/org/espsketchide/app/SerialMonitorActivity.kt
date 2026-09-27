package org.espsketchide.app

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.espsketchide.app.databinding.ActivitySerialMonitorBinding
import org.espsketchide.app.ui.Insets
import org.espsketchide.app.upload.SerialMonitor
import org.espsketchide.app.upload.UsbConnect

/** Shows what the board prints and sends lines to it, like the Arduino IDE's Serial Monitor. */
class SerialMonitorActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySerialMonitorBinding
    private val app get() = application as EspSketchApp
    private val prefs by lazy { getSharedPreferences("esp_sketch_ide", MODE_PRIVATE) }
    private var baud: Int
        get() = prefs.getInt(KEY_BAUD, 115_200)
        set(value) = prefs.edit().putInt(KEY_BAUD, value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySerialMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applyToAppBar(binding.appBar)
        Insets.applyBottomIncludingKeyboard(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.monitor_title)

        binding.send.setOnClickListener { send() }
        binding.input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }

        val existing = app.serialMonitor
        if (existing != null) observe(existing) else connect()
    }

    private fun connect() {
        lifecycleScope.launch {
            val link = UsbConnect.pickAndOpen(this@SerialMonitorActivity, baud) { message ->
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            } ?: return@launch
            val monitor = SerialMonitor(link).start()
            app.serialMonitor = monitor
            observe(monitor)
        }
    }

    private fun observe(monitor: SerialMonitor) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    monitor.text.collect { text ->
                        val scroll = binding.outputScroll
                        val atEnd = !scroll.canScrollVertically(1)
                        binding.output.text = text
                        if (atEnd) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                    }
                }
                launch {
                    monitor.error.collect { error ->
                        if (error != null) {
                            Snackbar.make(binding.root, getString(R.string.monitor_disconnected, error), Snackbar.LENGTH_INDEFINITE).show()
                        }
                    }
                }
            }
        }
    }

    private fun send() {
        val monitor = app.serialMonitor ?: return
        val line = binding.input.text.toString()
        binding.input.text.clear()
        lifecycleScope.launch(Dispatchers.IO) { runCatching { monitor.send(line) } }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.serial_monitor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_baud)?.title = getString(R.string.monitor_baud, baud)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val monitor = app.serialMonitor
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_clear -> { monitor?.clear(); true }
            R.id.action_reset -> {
                if (monitor != null) lifecycleScope.launch(Dispatchers.IO) { runCatching { monitor.resetBoard() } }
                true
            }
            R.id.action_baud -> { chooseBaud(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun chooseBaud() {
        val labels = BAUD_RATES.map { it.toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.monitor_baud, baud))
            .setSingleChoiceItems(labels, BAUD_RATES.indexOf(baud)) { dialog, which ->
                baud = BAUD_RATES[which]
                app.serialMonitor?.let { m -> lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching { m.setBaudRate(baud) } } } }
                invalidateOptionsMenu()
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Rotation keeps the connection; leaving the screen closes it so Upload can use the port.
        if (isFinishing) {
            app.serialMonitor?.let { m -> app.appScope.launch { m.close() } }
            app.serialMonitor = null
        }
    }

    private companion object {
        const val KEY_BAUD = "monitor_baud"
        val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 74880, 115200, 230400, 460800, 921600)
    }
}
