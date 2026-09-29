# Phase 3: Built-in Content Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a new user something to start from: a catalog of built-in
example sketches (per board), an **Examples** screen that opens one as a new
sketch, and sketch templates in the New sketch dialog.

**Architecture:** Plain Activities and ViewBinding. New units:

- **`data/SketchNames`** (pure): `nextFreeName`, `toSketchName`, `renamePrimary`.
- **`examples/ExampleCatalog`** (pure): parses `assets/examples/index.json`,
  filters by board, groups by category, flattens to header/item rows.
- **`examples/ExampleFiles`**: reads a variant folder from assets.
- **`examples/ExamplesActivity` + `ExampleAdapter`**: the sectioned list and the
  "Open as new sketch" flow.
- **`templates/SketchTemplates`** (pure): three templates, `contentFor(id, board)`.
- **`SketchRepository`**: `createSketch(name, files)`, `uniqueName(base)`.
- **`SketchListActivity`**: Examples menu entry, empty-state button, template
  dropdown in the New sketch dialog.

**Tech Stack:** as Phases 1 and 2, plus `testImplementation("org.json:json:20240303")`
(Android's `org.json` is a stub on the JVM).

**Spec:** `docs/superpowers/specs/2026-09-27-ide-editor-overhaul-design.md`

**Deviations from the spec (decided while planning):**
1. **Template bodies are Kotlin string constants**, not files in
   `assets/templates/`. That makes "each template/board pair returns content
   and the WiFi header matches the board" a JVM test that runs anywhere, and it
   drops the "every template asset exists" instrumented check, which no longer
   has anything to check.
2. **`ExampleAssetsTest` is a JVM test**, not an instrumented one. Gradle runs
   unit tests with the module directory as the working directory, so the test
   reads `src/main/assets/examples` directly. It checks that every variant path
   exists and holds a `.ino`, every folder is referenced, every file has an
   editable extension, every sketch has `setup()` and `loop()`, balanced braces,
   and that a board's variant only includes its own WiFi headers.
3. **One "Saved counter" example** with an ESP32 (`Preferences`) and an ESP8266
   (`EEPROM`) variant, instead of two separate entries.
4. `uniqueName` checks every entry in the sketchbook root (files as well as
   folders), because `createDirectory` would collide with either.
5. All files of a new sketch are created with MIME `application/octet-stream`
   (as `addFile` already does) so providers never append an extension.
6. Nothing here adds UI for compile, upload or serial.

**Every Gradle command** needs JDK 17–21
(`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`). Paths are relative to
`EspSketchIDE/`.

**Commit trailer:**

```
Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KLrUbTsFZqUuFtfLxHVfJr
```

---

## File map

**New:** `data/SketchNames.kt`, `examples/{ExampleCatalog,ExampleFiles,ExamplesActivity,ExampleAdapter}.kt`,
`templates/SketchTemplates.kt`, `assets/examples/index.json` plus 22 variant
folders, layouts `activity_examples`, `item_example`, `item_example_header`,
`dialog_new_sketch`, and JVM tests `SketchNamesTest`, `ExampleCatalogTest`,
`SketchTemplatesTest`, `ExampleAssetsTest`.

**Modified:** `app/build.gradle.kts`, `SketchRepository.kt`,
`SketchListActivity.kt`, `activity_sketch_list.xml`, `sketch_list_toolbar.xml`,
`AndroidManifest.xml`, `strings.xml`, `assets/licenses/NOTICES.md`, `README.md`,
`CLAUDE.md`.

---

### Task 1: Pure logic with tests (TDD)

- [ ] **Step 1: Add the JVM dependency** `testImplementation("org.json:json:20240303")`.
- [ ] **Step 2: Tests first:** `SketchNamesTest` (free-name search `base`, `base_2`,
  `base_3`, gaps; `toSketchName` strips symbols and prefixes a non-letter start;
  `renamePrimary` renames the `.ino` and leaves other files), `ExampleCatalogTest`
  (parse, board filter, category grouping in index order, unknown boards ignored,
  flatten to rows), `SketchTemplatesTest` (every id × board returns content with
  `setup()`/`loop()`, the WiFi template includes `WiFi.h` on ESP32 and
  `ESP8266WiFi.h` on ESP8266, unknown ids fall back to bare minimum).
- [ ] **Step 3: Run, expect compile failures.**
- [ ] **Step 4: Implement** the three classes (sources in the appendix).
- [ ] **Step 5: Run, expect PASS. Commit** `Add pure logic for examples, templates and sketch names`.

### Task 2: Example sketches and the asset test

- [ ] **Step 1: Write `ExampleAssetsTest`** (see deviation 2).
- [ ] **Step 2: Write `assets/examples/index.json`** and the 22 variant folders:

  | Category | Example | Variants |
  |---|---|---|
  | Basics | Blink, BlinkWithoutDelay, Button, Fade | shared `basics/<Name>` |
  | Basics | AnalogRead | `basics/AnalogRead_esp32`, `basics/AnalogRead_esp8266` |
  | Communication | SerialEcho | shared |
  | WiFi | WiFiScan, WiFiConnect, HttpGet, WebServerHello, NtpClock | `wifi/<Name>_esp32`, `wifi/<Name>_esp8266` |
  | ESP-specific | DeepSleepTimer | `esp/DeepSleepTimer_esp32`, `_esp8266` |
  | ESP-specific | TouchSensor | ESP32 only |
  | ESP-specific | SavedCounter | Preferences (ESP32) / EEPROM (ESP8266) |

  Targets: ESP32 Arduino core 3.x and ESP8266 core 3.x. All sketches are written
  for this project (MIT). Where a pin differs it is a commented `const int`.
- [ ] **Step 3: Run the JVM tests, expect PASS. Commit** `Add built-in example sketches`.

### Task 3: Repository support

- [ ] `createSketch(name, files: Map<String, String>)`, with the existing
  single-argument call kept working (default content: the bare-minimum
  template). A failed write deletes the half-made folder.
- [ ] `uniqueName(base)` via `SketchNames.nextFreeName` over every entry of the
  root.
- [ ] Build; commit `SketchRepository: create sketches from file maps, unique names`.

### Task 4: UI

- [ ] `ExamplesActivity`: toolbar subtitle `<Board> · change in Settings`,
  sectioned RecyclerView, tap → root picker if there is no sketchbook yet, then
  the **Open as new sketch** dialog with a unique name prefilled, then copy the
  variant, open `EditorActivity`, and finish.
- [ ] Entry points: sketch-list ⋮ → **Examples**, and the empty-state
  **Browse examples** button.
- [ ] New sketch dialog gets a template dropdown (Material exposed dropdown,
  default Bare minimum); the chosen template is rendered for the current board.
- [ ] Manifest entry; strings.
- [ ] `assembleDebug`, `lint`; commit `Examples screen, template dropdown and entry points`.

### Task 5: Docs and verification

- [ ] `NOTICES.md` (examples are project-written, MIT), README, CLAUDE.md.
- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint :app:compileDebugAndroidTestKotlin`.
- [ ] Manual (needs a device/emulator, not run in the cloud session): empty
  state → Browse examples → open Blink → editor shows it and the sketch appears
  in the list; open it again and see `Blink_2`; switch the board to ESP8266 and
  check the list and the WiFi variants; New sketch → each template; light and
  dark.
- [ ] Optional (large download, needs the user's OK): compile every variant with
  `arduino-cli` and the `esp32:esp32` / `esp8266:esp8266` cores.
- [ ] Commit `Document Phase 3`.

---

## Appendix: final sources as committed

The exact files that were implemented and verified (unit tests, `assembleDebug`,
`lint`, `compileDebugAndroidTestKotlin`). The example sketches themselves are in
`app/src/main/assets/examples/`; `SketchRepository`, `SketchListActivity` and the
other layouts are in the commits named in the tasks above.

### `app/src/main/java/org/espsketchide/app/data/SketchNames.kt`

```kotlin
package org.espsketchide.app.data

/** Pure helpers for naming sketches. Sketch names must match `^[A-Za-z][A-Za-z0-9_]*$`. */
object SketchNames {

    /** [base] if it is free, otherwise the first of `base_2`, `base_3`, … that is. */
    fun nextFreeName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var n = 2
        while ("${base}_$n" in existing) n++
        return "${base}_$n"
    }

    /** A valid sketch name from a display name: drops other characters, prefixes `sketch_` if it starts with a non-letter. */
    fun toSketchName(displayName: String): String {
        val cleaned = displayName.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }
        if (cleaned.isEmpty()) return "sketch"
        return if (cleaned.first().isLetter()) cleaned else "sketch_$cleaned"
    }

    /** Renames the (first) `.ino` in [files] to `<newName>.ino`, keeping order and all other files. */
    fun renamePrimary(files: Map<String, String>, newName: String): Map<String, String> {
        val primary = files.keys.firstOrNull { it.endsWith(".ino") } ?: return files
        val renamed = LinkedHashMap<String, String>()
        for ((name, content) in files) {
            renamed[if (name == primary) "$newName.ino" else name] = content
        }
        return renamed
    }
}
```

### `app/src/main/java/org/espsketchide/app/examples/ExampleCatalog.kt`

```kotlin
package org.espsketchide.app.examples

import org.espsketchide.app.settings.Board
import org.json.JSONObject

/** One entry of `assets/examples/index.json`. [variants] maps a board key to a folder under `assets/examples/`. */
data class Example(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val variants: Map<String, String>,
)

/** A row of the Examples list: a category header or an example. */
sealed interface ExampleRow {
    data class Header(val title: String) : ExampleRow
    data class Item(val example: Example) : ExampleRow
}

object ExampleCatalog {

    fun parse(json: String): List<Example> {
        val array = JSONObject(json).getJSONArray("examples")
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            val variants = entry.getJSONObject("variants")
            Example(
                id = entry.getString("id"),
                name = entry.getString("name"),
                category = entry.getString("category"),
                description = entry.optString("description"),
                variants = variants.keys().asSequence().associateWith { variants.getString(it) },
            )
        }
    }

    /** Examples that have a variant for [board], grouped by category in order of first appearance. */
    fun forBoard(examples: List<Example>, board: Board): List<Pair<String, List<Example>>> =
        examples.filter { variantPath(it, board) != null }
            .groupBy { it.category }
            .toList()

    fun variantPath(example: Example, board: Board): String? = example.variants[board.key]

    fun rows(groups: List<Pair<String, List<Example>>>): List<ExampleRow> =
        groups.flatMap { (category, items) -> listOf<ExampleRow>(ExampleRow.Header(category)) + items.map { ExampleRow.Item(it) } }
}
```

### `app/src/main/java/org/espsketchide/app/examples/ExampleFiles.kt`

```kotlin
package org.espsketchide.app.examples

import android.content.res.AssetManager

object ExampleFiles {

    private const val ROOT = "examples"

    /** Reads every file of a variant folder (a path from the index, e.g. `basics/Blink`) as name to text. */
    fun read(assets: AssetManager, variantPath: String): Map<String, String> {
        val dir = "$ROOT/$variantPath"
        return assets.list(dir).orEmpty().sorted().associateWith { name ->
            assets.open("$dir/$name").bufferedReader().use { it.readText() }
        }
    }

    fun readIndex(assets: AssetManager): String =
        assets.open("$ROOT/index.json").bufferedReader().use { it.readText() }
}
```

### `app/src/main/java/org/espsketchide/app/examples/ExamplesActivity.kt`

```kotlin
package org.espsketchide.app.examples

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import org.espsketchide.app.EditorActivity
import org.espsketchide.app.R
import org.espsketchide.app.data.SketchAlreadyExistsException
import org.espsketchide.app.data.SketchNameInvalidException
import org.espsketchide.app.data.SketchNames
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.databinding.ActivityExamplesBinding
import org.espsketchide.app.settings.AppSettings
import org.espsketchide.app.settings.Board
import java.io.IOException

/** Lists the built-in examples for the selected board and opens one as a new sketch. */
class ExamplesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamplesBinding
    private lateinit var repository: SketchRepository
    private lateinit var board: Board

    /** The example the user tapped while there was no sketchbook folder yet. */
    private var pendingExample: Example? = null

    private val pickRootFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val example = pendingExample
        pendingExample = null
        if (uri != null) {
            repository.setRoot(uri)
            if (example != null) promptOpenAsSketch(example)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamplesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        repository = SketchRepository(this)
        board = AppSettings(this).board
        supportActionBar?.subtitle = getString(R.string.examples_subtitle, getString(boardLabel(board)))

        val catalog = ExampleCatalog.parse(ExampleFiles.readIndex(assets))
        val rows = ExampleCatalog.rows(ExampleCatalog.forBoard(catalog, board))
        binding.exampleRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.exampleRecyclerView.adapter = ExampleAdapter(rows) { example -> onExampleClicked(example) }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun boardLabel(board: Board): Int = when (board) {
        Board.ESP32 -> R.string.board_esp32
        Board.ESP8266 -> R.string.board_esp8266
    }

    private fun onExampleClicked(example: Example) {
        if (!repository.hasRoot()) {
            pendingExample = example
            pickRootFolder.launch(null)
            return
        }
        promptOpenAsSketch(example)
    }

    private fun promptOpenAsSketch(example: Example) {
        val input = EditText(this).apply {
            setText(repository.uniqueName(SketchNames.toSketchName(example.name)))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_open_example_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                openAsSketch(example, input.text.toString().trim())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openAsSketch(example: Example, name: String) {
        val path = ExampleCatalog.variantPath(example, board) ?: return
        try {
            val files = SketchNames.renamePrimary(ExampleFiles.read(assets, path), name)
            val sketch = repository.createSketch(name, files)
            startActivity(
                Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_SKETCH_NAME, sketch.name)
                    .putExtra(EditorActivity.EXTRA_SKETCH_FOLDER_URI, sketch.folderUri)
            )
            finish()
        } catch (e: SketchNameInvalidException) {
            toast(getString(R.string.error_invalid_sketch_name))
        } catch (e: SketchAlreadyExistsException) {
            toast(getString(R.string.error_sketch_exists, name))
        } catch (e: IOException) {
            toast(getString(R.string.error_create_failed))
        } catch (e: IllegalStateException) {
            toast(getString(R.string.error_create_failed))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
```

### `app/src/main/java/org/espsketchide/app/examples/ExampleAdapter.kt`

```kotlin
package org.espsketchide.app.examples

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.espsketchide.app.databinding.ItemExampleBinding
import org.espsketchide.app.databinding.ItemExampleHeaderBinding

class ExampleAdapter(
    private val rows: List<ExampleRow>,
    private val onClick: (Example) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is ExampleRow.Header -> TYPE_HEADER
        is ExampleRow.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemExampleHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemExampleBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ExampleRow.Header -> (holder as HeaderHolder).binding.exampleHeaderText.text = row.title
            is ExampleRow.Item -> {
                val binding = (holder as ItemHolder).binding
                binding.exampleNameText.text = row.example.name
                binding.exampleDescriptionText.text = row.example.description
                binding.root.setOnClickListener { onClick(row.example) }
            }
        }
    }

    private class HeaderHolder(val binding: ItemExampleHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class ItemHolder(val binding: ItemExampleBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }
}
```

### `app/src/main/java/org/espsketchide/app/templates/SketchTemplates.kt`

```kotlin
package org.espsketchide.app.templates

import org.espsketchide.app.settings.Board

/** Starting points offered by the New sketch dialog. Bodies are plain constants so they are JVM-testable. */
object SketchTemplates {

    const val BARE = "bare"
    const val SERIAL = "serial"
    const val WIFI_STATION = "wifi_station"

    /** In dialog order; the first is the default. */
    val ids: List<String> = listOf(BARE, SERIAL, WIFI_STATION)

    fun contentFor(templateId: String, board: Board): String = when (templateId) {
        SERIAL -> SERIAL_TEMPLATE
        WIFI_STATION -> wifiStation(board)
        else -> BARE_TEMPLATE
    }

    private val BARE_TEMPLATE = """
        void setup() {
          // Runs once at start-up.

        }

        void loop() {
          // Runs over and over.

        }
    """.trimIndent() + "\n"

    private val SERIAL_TEMPLATE = """
        void setup() {
          Serial.begin(115200);
          delay(500);
          Serial.println("Hello from the ESP");
        }

        void loop() {
          Serial.println(millis());
          delay(1000);
        }
    """.trimIndent() + "\n"

    private fun wifiStation(board: Board): String {
        val header = when (board) {
            Board.ESP32 -> "WiFi.h"
            Board.ESP8266 -> "ESP8266WiFi.h"
        }
        return """
            #include <$header>

            // Replace with your network.
            const char* WIFI_SSID = "your-network";
            const char* WIFI_PASSWORD = "your-password";

            void setup() {
              Serial.begin(115200);
              WiFi.mode(WIFI_STA);
              WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
              Serial.print("Connecting");
              while (WiFi.status() != WL_CONNECTED) {
                delay(500);
                Serial.print(".");
              }
              Serial.println();
              Serial.print("IP address: ");
              Serial.println(WiFi.localIP());
            }

            void loop() {

            }
        """.trimIndent() + "\n"
    }
}
```

### `app/src/main/res/layout/dialog_new_sketch.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingStart="24dp"
    android:paddingTop="8dp"
    android:paddingEnd="24dp">

    <com.google.android.material.textfield.TextInputLayout
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/dialog_new_sketch_hint">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/sketchNameInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="@font/jetbrains_mono_regular"
            android:imeOptions="actionDone"
            android:inputType="text"
            android:maxLines="1" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:hint="@string/dialog_new_sketch_template">

        <com.google.android.material.textfield.MaterialAutoCompleteTextView
            android:id="@+id/templateDropdown"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="none" />
    </com.google.android.material.textfield.TextInputLayout>

</LinearLayout>
```

### `app/src/main/assets/examples/index.json`

```json
{
  "examples": [
    {
      "id": "blink",
      "name": "Blink",
      "category": "Basics",
      "description": "Blink the on-board LED once per second.",
      "variants": {
        "esp32": "basics/Blink",
        "esp8266": "basics/Blink"
      }
    },
    {
      "id": "blink_without_delay",
      "name": "BlinkWithoutDelay",
      "category": "Basics",
      "description": "Blink the LED with millis() so the loop never blocks.",
      "variants": {
        "esp32": "basics/BlinkWithoutDelay",
        "esp8266": "basics/BlinkWithoutDelay"
      }
    },
    {
      "id": "button",
      "name": "Button",
      "category": "Basics",
      "description": "Read a button with the internal pull-up resistor.",
      "variants": {
        "esp32": "basics/Button",
        "esp8266": "basics/Button"
      }
    },
    {
      "id": "analog_read",
      "name": "AnalogRead",
      "category": "Basics",
      "description": "Read a voltage on an analog pin and print it.",
      "variants": {
        "esp32": "basics/AnalogRead_esp32",
        "esp8266": "basics/AnalogRead_esp8266"
      }
    },
    {
      "id": "fade",
      "name": "Fade",
      "category": "Basics",
      "description": "Fade the LED in and out with PWM (analogWrite).",
      "variants": {
        "esp32": "basics/Fade",
        "esp8266": "basics/Fade"
      }
    },
    {
      "id": "serial_echo",
      "name": "SerialEcho",
      "category": "Communication",
      "description": "Echo everything received on the serial port.",
      "variants": {
        "esp32": "comm/SerialEcho",
        "esp8266": "comm/SerialEcho"
      }
    },
    {
      "id": "wifi_scan",
      "name": "WiFiScan",
      "category": "WiFi",
      "description": "List the Wi-Fi networks in range.",
      "variants": {
        "esp32": "wifi/WiFiScan_esp32",
        "esp8266": "wifi/WiFiScan_esp8266"
      }
    },
    {
      "id": "wifi_connect",
      "name": "WiFiConnect",
      "category": "WiFi",
      "description": "Join a Wi-Fi network and print the IP address.",
      "variants": {
        "esp32": "wifi/WiFiConnect_esp32",
        "esp8266": "wifi/WiFiConnect_esp8266"
      }
    },
    {
      "id": "http_get",
      "name": "HttpGet",
      "category": "WiFi",
      "description": "Fetch a web page over HTTP.",
      "variants": {
        "esp32": "wifi/HttpGet_esp32",
        "esp8266": "wifi/HttpGet_esp8266"
      }
    },
    {
      "id": "web_server_hello",
      "name": "WebServerHello",
      "category": "WiFi",
      "description": "A tiny web server that answers Hello.",
      "variants": {
        "esp32": "wifi/WebServerHello_esp32",
        "esp8266": "wifi/WebServerHello_esp8266"
      }
    },
    {
      "id": "ntp_clock",
      "name": "NtpClock",
      "category": "WiFi",
      "description": "Sync the clock over NTP and print the time.",
      "variants": {
        "esp32": "wifi/NtpClock_esp32",
        "esp8266": "wifi/NtpClock_esp8266"
      }
    },
    {
      "id": "deep_sleep_timer",
      "name": "DeepSleepTimer",
      "category": "ESP-specific",
      "description": "Sleep for ten seconds, wake up, repeat.",
      "variants": {
        "esp32": "esp/DeepSleepTimer_esp32",
        "esp8266": "esp/DeepSleepTimer_esp8266"
      }
    },
    {
      "id": "touch_sensor",
      "name": "TouchSensor",
      "category": "ESP-specific",
      "description": "Read a capacitive touch pin (classic ESP32 only).",
      "variants": {
        "esp32": "esp/TouchSensor"
      }
    },
    {
      "id": "saved_counter",
      "name": "SavedCounter",
      "category": "ESP-specific",
      "description": "Count restarts and keep the count in flash.",
      "variants": {
        "esp32": "esp/SavedCounter_esp32",
        "esp8266": "esp/SavedCounter_esp8266"
      }
    }
  ]
}
```
