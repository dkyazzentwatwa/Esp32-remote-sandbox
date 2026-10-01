package org.espsketchide.app.examples

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.espsketchide.app.R
import org.espsketchide.app.databinding.ActivityExamplesBinding
import org.espsketchide.app.settings.AppSettings
import org.espsketchide.app.settings.Board
import org.espsketchide.app.ui.Insets

/**
 * Lists the built-in examples for the selected board. Picking one returns its id
 * ([RESULT_EXAMPLE_ID]); the sketch list copies it into a new sketch and opens it.
 */
class ExamplesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamplesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityExamplesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applyToAppBar(binding.appBar)
        Insets.applyBottomPadding(binding.exampleRecyclerView)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val board = AppSettings(this).board
        supportActionBar?.subtitle = getString(R.string.examples_subtitle, getString(boardLabel(board)))
        binding.exampleRecyclerView.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val catalog = ExampleCatalog.parse(ExampleFiles.readIndex(assets))
                ExampleCatalog.rows(ExampleCatalog.forBoard(catalog, board))
            }
            binding.exampleRecyclerView.adapter = ExampleAdapter(rows) { example ->
                setResult(RESULT_OK, Intent().putExtra(RESULT_EXAMPLE_ID, example.id))
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun boardLabel(board: Board): Int = when (board) {
        Board.ESP32 -> R.string.board_esp32
        Board.ESP8266 -> R.string.board_esp8266
    }

    companion object {
        const val RESULT_EXAMPLE_ID = "example_id"
    }
}
