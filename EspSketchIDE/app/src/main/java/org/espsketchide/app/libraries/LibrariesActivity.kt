package org.espsketchide.app.libraries

import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.espsketchide.app.EspSketchApp
import org.espsketchide.app.R
import org.espsketchide.app.databinding.ActivityLibrariesBinding
import org.espsketchide.app.databinding.ItemExampleHeaderBinding
import org.espsketchide.app.databinding.ItemLibraryBinding
import org.espsketchide.app.settings.AppSettings
import org.espsketchide.app.ui.Insets

/** Install or remove libraries from the curated catalog, or install a library .zip. */
class LibrariesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibrariesBinding
    private val manager get() = (application as EspSketchApp).libraries
    private val adapter = LibraryAdapter()

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) manager.installZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLibrariesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applyToAppBar(binding.appBar)
        Insets.applyBottomPadding(binding.libraryList)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val board = AppSettings(this).board
        val groups = manager.catalog.visible(board).groupBy { it.category }
        adapter.rows = groups.flatMap { (category, libs) -> listOf<Any>(category) + libs }
        binding.libraryList.layoutManager = LinearLayoutManager(this)
        binding.libraryList.adapter = adapter

        manager.refresh()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.state.collect { adapter.state = it; adapter.notifyDataSetChanged() } }
                launch { manager.events.collect(::showEvent) }
            }
        }
    }

    private fun showEvent(event: LibraryEvent) {
        val text = when (event) {
            is LibraryEvent.Installed -> getString(R.string.libraries_installed, event.names.joinToString())
            is LibraryEvent.Removed -> getString(R.string.libraries_removed, event.name)
            is LibraryEvent.Failed -> event.message
        }
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.libraries, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_install_zip -> { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")); true }
        else -> super.onOptionsItemSelected(item)
    }

    private inner class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Any> = emptyList()
        var state = LibrariesState()

        override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                object : RecyclerView.ViewHolder(ItemExampleHeaderBinding.inflate(inflater, parent, false).root) {}
            } else {
                object : RecyclerView.ViewHolder(ItemLibraryBinding.inflate(inflater, parent, false).root) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (row is String) {
                (holder.itemView as TextView).text = row
                return
            }
            val library = row as CatalogLibrary
            val b = ItemLibraryBinding.bind(holder.itemView)
            b.libraryName.text = library.name
            b.libraryMeta.text = getString(R.string.libraries_meta, library.version, Formatter.formatShortFileSize(this@LibrariesActivity, library.size))
            b.libraryDescription.text = library.description
            val installed = library.name in state.installed
            val busy = state.working != null
            b.libraryAction.isEnabled = !busy
            b.libraryAction.text = when {
                state.working == library.name -> getString(R.string.libraries_working)
                installed -> getString(R.string.libraries_remove)
                else -> getString(R.string.libraries_install)
            }
            b.libraryAction.setOnClickListener { if (installed) manager.remove(library.name) else manager.install(library.name) }
        }
    }
}
