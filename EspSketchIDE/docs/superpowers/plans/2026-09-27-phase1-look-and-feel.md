# Phase 1: Look & Feel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make EspSketchIDE look like a real IDE. That means a dark-by-default
theme with a toggle, real Arduino/C++ syntax highlighting via TextMate, a
monospace editor font, proper icons, and a Settings screen.

**Architecture:** Plain Activities and ViewBinding, as today. There are four
new units:

- **`settings/AppSettings`:** typed access to SharedPreferences.
- **`EspSketchApp`:** applies the night mode at startup and starts loading
  grammars.
- **`SettingsActivity`:** an androidx Preference screen.
- **`editor/EditorLanguages`:** one-time sora TextMate setup, plus the
  language and color scheme for each file.

Arduino-specific highlighting is an in-grammar TextMate **injection** in
`arduino.tmLanguage.json` layered on top of VS Code's C++ grammar. An injection
is needed because the C++ grammar uses `$self` rather than `$base` inside
blocks, so plain top-level patterns would never reach inside a function body.

**Tech Stack:** Kotlin 2.1.21, AGP 8.5.2, Gradle 8.7, Material Components
1.12, androidx.preference 1.2.1, sora-editor 0.23.6 (`editor` +
`language-textmate`), and JUnit4 with AndroidX Test on the
`EspSketchIDE_API34` emulator.

**Spec:** `docs/superpowers/specs/2026-09-27-ide-editor-overhaul-design.md`

**Deviations from the spec (decided while planning):**
1. The `board` and `show_symbol_bar` settings move to Phase 2, next to the
   features that use them. In Phase 1 they would be settings that do nothing,
   which is fake UI.
2. The third-party notices live in one file,
   `app/src/main/assets/licenses/NOTICES.md` (shown in the app and linked
   from the README), instead of a root file plus an asset copy (DRY).
3. The Arduino grammar uses an injection
   (`"L:source.arduino -string -comment"`) rather than listing its patterns
   first (see Architecture).
4. sora-editor is **LGPL-2.1**, not MIT as the old `build.gradle.kts` comment
   claimed. That comment gets corrected, and sora is listed in the notices.

**Every Gradle command** in this plan needs JDK 21. Run this first in each
shell:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

All paths below are relative to `EspSketchIDE/`, the project root. The git
root is its parent directory.

---

## File map

**New files:**

| Path | Contents |
|---|---|
| `app/src/main/assets/textmate/cpp/cpp.tmLanguage.json` | vendored, VS Code, MIT |
| `app/src/main/assets/textmate/cpp/cpp.embedded.macro.tmLanguage.json` | vendored, VS Code, MIT |
| `app/src/main/assets/textmate/cpp/language-configuration.json` | vendored, VS Code, MIT |
| `app/src/main/assets/textmate/arduino.tmLanguage.json` | Arduino injection grammar |
| `app/src/main/assets/textmate/languages.json` | grammar registry for sora |
| `app/src/main/assets/textmate/themes/ide-dark.json` | our dark editor theme |
| `app/src/main/assets/textmate/themes/ide-light.json` | our light editor theme |
| `app/src/main/assets/licenses/NOTICES.md` | third-party notices |
| `app/src/main/assets/licenses/vscode-LICENSE.txt` | license text |
| `app/src/main/assets/licenses/JetBrainsMono-OFL.txt` | license text |
| `app/src/main/assets/licenses/material-design-icons-LICENSE.txt` | license text |
| `app/src/main/res/font/jetbrains_mono_regular.ttf` | editor font, OFL-1.1 |
| `app/src/main/res/drawable/ic_*.xml` | 14 Material Symbols icons, Apache-2.0 |
| `app/src/main/java/org/espsketchide/app/EspSketchApp.kt` | Application class |
| `app/src/main/java/org/espsketchide/app/settings/AppSettings.kt` | `ThemeMode`, `EditorFontSize`, `AppSettings`, pref keys |
| `app/src/main/java/org/espsketchide/app/settings/SettingsActivity.kt` | activity plus `SettingsFragment` |
| `app/src/main/java/org/espsketchide/app/editor/EditorLanguages.kt` | TextMate setup |
| `app/src/main/res/layout/activity_settings.xml` | settings screen layout |
| `app/src/main/res/xml/preferences.xml` | preference definitions |
| `app/src/main/res/values/arrays.xml` | theme entries and values |
| `app/src/main/res/values/bools.xml` | light system bars on |
| `app/src/main/res/values-night/bools.xml` | light system bars off |
| `app/src/main/res/values-night/colors.xml` | night palette |
| `app/src/test/java/org/espsketchide/app/settings/AppSettingsTest.kt` | JVM tests |
| `app/src/androidTest/java/org/espsketchide/app/editor/HighlightingTest.kt` | instrumented test |

**Modified files:**
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/org/espsketchide/app/EditorActivity.kt`
- `app/src/main/java/org/espsketchide/app/SketchListActivity.kt`
- `app/src/main/java/org/espsketchide/app/data/SketchRepository.kt` (shared
  prefs-name constant)
- `app/src/main/res/layout/activity_editor.xml`
- `app/src/main/res/layout/activity_sketch_list.xml`
- `app/src/main/res/layout/item_sketch.xml`
- `app/src/main/res/menu/editor_toolbar.xml`
- `app/src/main/res/menu/sketch_list_toolbar.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/themes.xml`
- `README.md`
- `CLAUDE.md`

**Deleted:** `app/src/main/res/values-night/themes.xml`. The night palette now
comes from `values-night/colors.xml`.

---

### Task 1: Vendor third-party assets and write the notices

**Files:**
- Create: the `textmate/cpp/*`, `licenses/*`, `res/font/*`, and
  `res/drawable/ic_*.xml` files listed in the file map.

- [ ] **Step 1: Download the pinned files.** They're pinned to the commits
  that were checked while planning. The user approved these downloads.

```bash
cd /Users/cypher/Documents/GitHub/Esp32-remote-sandbox/EspSketchIDE
R=https://raw.githubusercontent.com
VS=af600487b1e94374d9f48f57cbf2cad24656b07f
JB=02bb50b082dad9ef8a0f33ac393839202b760223
MD=6d7ca43bd6e6668531a00fcaca06d921b63dd716
A=app/src/main/assets
mkdir -p $A/textmate/cpp $A/textmate/themes $A/licenses app/src/main/res/font
curl -sSfLo $A/textmate/cpp/cpp.tmLanguage.json $R/microsoft/vscode/$VS/extensions/cpp/syntaxes/cpp.tmLanguage.json
curl -sSfLo $A/textmate/cpp/cpp.embedded.macro.tmLanguage.json $R/microsoft/vscode/$VS/extensions/cpp/syntaxes/cpp.embedded.macro.tmLanguage.json
curl -sSfLo $A/textmate/cpp/language-configuration.json $R/microsoft/vscode/$VS/extensions/cpp/language-configuration.json
curl -sSfLo $A/licenses/vscode-LICENSE.txt $R/microsoft/vscode/$VS/LICENSE.txt
curl -sSfLo app/src/main/res/font/jetbrains_mono_regular.ttf $R/JetBrains/JetBrainsMono/$JB/fonts/ttf/JetBrainsMono-Regular.ttf
curl -sSfLo $A/licenses/JetBrainsMono-OFL.txt $R/JetBrains/JetBrainsMono/$JB/OFL.txt
curl -sSfLo $A/licenses/material-design-icons-LICENSE.txt $R/google/material-design-icons/$MD/LICENSE
for n in save undo redo search add settings menu_book close keyboard_arrow_up keyboard_arrow_down expand_more more_vert folder_open description; do
  curl -sSfLo app/src/main/res/drawable/ic_$n.xml $R/google/material-design-icons/$MD/symbols/android/$n/materialsymbolsoutlined/${n}_24px.xml
done
ls -la $A/textmate/cpp $A/licenses app/src/main/res/font app/src/main/res/drawable
```

Expected output:
- `cpp.tmLanguage.json`: ~678 KB
- `cpp.embedded.macro.tmLanguage.json`: ~385 KB
- `jetbrains_mono_regular.ttf`: ~270 KB
- 14 `ic_*.xml` files, each starting with `<vector` and carrying
  `android:tint="?attr/colorControlNormal"`

- [ ] **Step 2: Write `app/src/main/assets/licenses/NOTICES.md`**

```markdown
# Third-party notices

EspSketchIDE is MIT-licensed (see LICENSE in the source repository). It
bundles or links the following third-party material.

## C/C++ TextMate grammar (Visual Studio Code)
- Files: `textmate/cpp/cpp.tmLanguage.json`,
  `textmate/cpp/cpp.embedded.macro.tmLanguage.json`,
  `textmate/cpp/language-configuration.json`
- Source: https://github.com/microsoft/vscode/tree/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/cpp
  (the grammar originates from https://github.com/jeff-hykin/better-cpp-syntax)
- License: MIT (see `vscode-LICENSE.txt`)

## JetBrains Mono
- File: `res/font/jetbrains_mono_regular.ttf`
- Source: https://github.com/JetBrains/JetBrainsMono (commit 02bb50b)
- License: SIL Open Font License 1.1 (see `JetBrainsMono-OFL.txt`)

## Material Symbols
- Files: `res/drawable/ic_*.xml` (except `ic_launcher_*`)
- Source: https://github.com/google/material-design-icons (commit 6d7ca43)
- License: Apache License 2.0 (see `material-design-icons-LICENSE.txt`)

## Libraries
- sora-editor (`editor`, `language-textmate`), https://github.com/Rosemoe/sora-editor:
  GNU LGPL 2.1, used unmodified as a library.
- Eclipse TM4E (bundled in `language-textmate`), https://github.com/eclipse/tm4e:
  Eclipse Public License 2.0.
- joni and jcodings, https://github.com/jruby: MIT.
- Gson, snakeyaml-engine: Apache License 2.0.
- AndroidX, Material Components for Android, Kotlin standard library:
  Apache License 2.0.
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets app/src/main/res/font app/src/main/res/drawable/ic_*.xml
git commit -m "Vendor C++ grammar, JetBrains Mono, Material Symbols icons, notices

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

`ic_launcher_*.xml` is already tracked and unchanged, so the glob only adds
the new icons.

---

### Task 2: Settings model (TDD)

**Files:**
- Create: `app/src/main/java/org/espsketchide/app/settings/AppSettings.kt`
- Modify: `app/src/main/java/org/espsketchide/app/data/SketchRepository.kt:13`
- Test: `app/src/test/java/org/espsketchide/app/settings/AppSettingsTest.kt`

- [ ] **Step 1: Write the failing test**
  `app/src/test/java/org/espsketchide/app/settings/AppSettingsTest.kt`

```kotlin
package org.espsketchide.app.settings

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `theme keys map to AppCompat night modes`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.fromKey("dark").nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.fromKey("light").nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.fromKey("system").nightMode)
    }

    @Test
    fun `missing or unknown theme key falls back to dark`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("sepia"))
    }

    @Test
    fun `font size is clamped to the supported range`() {
        assertEquals(10, EditorFontSize.clamp(4))
        assertEquals(14, EditorFontSize.clamp(14))
        assertEquals(24, EditorFontSize.clamp(99))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.espsketchide.app.settings.AppSettingsTest"`
Expected: FAIL, with compilation errors `Unresolved reference 'ThemeMode'` and
`'EditorFontSize'`.

- [ ] **Step 3: Implement**
  `app/src/main/java/org/espsketchide/app/settings/AppSettings.kt`

```kotlin
package org.espsketchide.app.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** SharedPreferences file shared by settings and [org.espsketchide.app.data.SketchRepository]. */
const val PREFS_NAME = "esp_sketch_ide"

const val KEY_THEME = "theme"
const val KEY_EDITOR_FONT_SIZE = "editor_font_size"
const val KEY_WORD_WRAP = "word_wrap"

enum class ThemeMode(val key: String, val nightMode: Int) {
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    companion object {
        val DEFAULT = DARK

        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

object EditorFontSize {
    const val MIN = 10
    const val MAX = 24
    const val DEFAULT = 14

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}

/** Read-only, typed view of the user's settings. The Settings screen writes them. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME, null))

    val editorFontSize: Int
        get() = EditorFontSize.clamp(prefs.getInt(KEY_EDITOR_FONT_SIZE, EditorFontSize.DEFAULT))

    val wordWrap: Boolean
        get() = prefs.getBoolean(KEY_WORD_WRAP, false)
}
```

- [ ] **Step 4: Make `SketchRepository` use the shared prefs name.** In
  `app/src/main/java/org/espsketchide/app/data/SketchRepository.kt`, delete
  the line `private const val PREFS_NAME = "esp_sketch_ide"` and add this
  import with the others:

```kotlin
import org.espsketchide.app.settings.PREFS_NAME
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.espsketchide.app.settings.AppSettingsTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/espsketchide/app/settings/AppSettings.kt app/src/main/java/org/espsketchide/app/data/SketchRepository.kt app/src/test
git commit -m "Add typed settings model with theme and font-size rules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Application class applies the theme at startup

**Files:**
- Create: `app/src/main/java/org/espsketchide/app/EspSketchApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create `EspSketchApp.kt`**

```kotlin
package org.espsketchide.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import org.espsketchide.app.settings.AppSettings

class EspSketchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppSettings(this).themeMode.nightMode)
    }
}
```

- [ ] **Step 2: Register it.** In `AndroidManifest.xml`, add
  `android:name=".EspSketchApp"` as the first attribute of `<application`:

```xml
    <application
        android:name=".EspSketchApp"
        android:allowBackup="true"
```

- [ ] **Step 3: Build and check on the emulator**

```bash
./gradlew :app:installDebug -q && adb shell am force-stop org.espsketchide.app && adb shell am start -n org.espsketchide.app/.SketchListActivity
adb shell cmd uimode night no
```

Expected: the app renders dark even though the system is in light mode (the
default theme key is `dark`). The screenshot
`adb exec-out screencap -p > /tmp/t3.png` shows a dark background.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/espsketchide/app/EspSketchApp.kt app/src/main/AndroidManifest.xml
git commit -m "Apply saved theme (dark by default) at app startup

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: IDE palette, bars, icons, and list styling

**Files:**
- Modify: `app/src/main/res/values/colors.xml`,
  `app/src/main/res/values/themes.xml`,
  `app/src/main/res/layout/activity_sketch_list.xml`,
  `app/src/main/res/layout/item_sketch.xml`,
  `app/src/main/res/layout/activity_editor.xml`,
  `app/src/main/res/menu/editor_toolbar.xml`,
  `app/src/main/java/org/espsketchide/app/SketchListActivity.kt`
- Create: `app/src/main/res/values-night/colors.xml`,
  `app/src/main/res/values/bools.xml`,
  `app/src/main/res/values-night/bools.xml`
- Delete: `app/src/main/res/values-night/themes.xml`

- [ ] **Step 1: Replace `values/colors.xml`** (light palette plus brand
  colors)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Brand -->
    <color name="esp_primary">#00838F</color>
    <color name="esp_accent">#FF6F00</color>
    <color name="esp_on_accent">#FFFFFF</color>

    <!-- Surfaces; night values live in values-night/colors.xml -->
    <color name="esp_background">#FFFFFF</color>
    <color name="esp_surface">#F7F8FA</color>
    <color name="esp_bar">#F2F3F5</color>
    <color name="esp_on_bar">#1F2328</color>
    <color name="esp_on_bar_muted">#6C707E</color>
    <color name="esp_on_surface">#1F2328</color>
    <color name="esp_on_surface_muted">#6C707E</color>
    <color name="esp_divider">#DFE1E5</color>
</resources>
```

- [ ] **Step 2: Create `values-night/colors.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="esp_primary">#4DB6AC</color>
    <color name="esp_background">#1E1F22</color>
    <color name="esp_surface">#2B2D30</color>
    <color name="esp_bar">#2B2D30</color>
    <color name="esp_on_bar">#DFE1E5</color>
    <color name="esp_on_bar_muted">#9DA0A8</color>
    <color name="esp_on_surface">#DFE1E5</color>
    <color name="esp_on_surface_muted">#9DA0A8</color>
    <color name="esp_divider">#393B40</color>
</resources>
```

- [ ] **Step 3: Create `values/bools.xml` and `values-night/bools.xml`**

`values/bools.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <bool name="esp_light_bars">true</bool>
</resources>
```

`values-night/bools.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <bool name="esp_light_bars">false</bool>
</resources>
```

- [ ] **Step 4: Replace `values/themes.xml`, and delete
  `values-night/themes.xml`**

```xml
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.EspSketchIDE" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/esp_primary</item>
        <item name="colorOnPrimary">@color/esp_on_accent</item>
        <item name="colorSecondary">@color/esp_accent</item>
        <item name="colorOnSecondary">@color/esp_on_accent</item>
        <item name="android:colorBackground">@color/esp_background</item>
        <item name="colorSurface">@color/esp_surface</item>
        <item name="colorSurfaceContainer">@color/esp_surface</item>
        <item name="colorSurfaceContainerHigh">@color/esp_surface</item>
        <item name="colorSurfaceContainerHighest">@color/esp_surface</item>
        <item name="colorOnSurface">@color/esp_on_surface</item>
        <item name="colorOnSurfaceVariant">@color/esp_on_surface_muted</item>
        <item name="colorOutlineVariant">@color/esp_divider</item>
        <item name="android:statusBarColor">@color/esp_bar</item>
        <item name="android:windowLightStatusBar">@bool/esp_light_bars</item>
        <item name="android:navigationBarColor">@color/esp_background</item>
        <item name="android:windowLightNavigationBar" tools:targetApi="27">@bool/esp_light_bars</item>
    </style>

    <!-- Put on every AppBarLayout so titles, icons and overflow use the bar colors. -->
    <style name="ThemeOverlay.EspSketchIDE.Bar" parent="">
        <item name="colorControlNormal">@color/esp_on_bar</item>
        <item name="colorOnSurface">@color/esp_on_bar</item>
        <item name="colorOnSurfaceVariant">@color/esp_on_bar_muted</item>
        <item name="android:textColorPrimary">@color/esp_on_bar</item>
        <item name="android:textColorSecondary">@color/esp_on_bar_muted</item>
    </style>

    <style name="TextAppearance.EspSketchIDE.Tab" parent="TextAppearance.Material3.TitleSmall">
        <item name="fontFamily">@font/jetbrains_mono_regular</item>
        <item name="android:fontFamily">@font/jetbrains_mono_regular</item>
        <item name="android:textAllCaps">false</item>
        <item name="android:textSize">13sp</item>
    </style>
</resources>
```

```bash
git rm -q app/src/main/res/values-night/themes.xml
```

- [ ] **Step 5: Replace `layout/activity_sketch_list.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@color/esp_bar"
        android:theme="@style/ThemeOverlay.EspSketchIDE.Bar"
        app:elevation="0dp"
        app:liftOnScroll="false">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:title="@string/sketch_list_title" />

        <View
            android:layout_width="match_parent"
            android:layout_height="1dp"
            android:background="@color/esp_divider" />

    </com.google.android.material.appbar.AppBarLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/sketchRecyclerView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipToPadding="false"
        android:paddingBottom="88dp"
        app:layout_behavior="@string/appbar_scrolling_view_behavior" />

    <TextView
        android:id="@+id/emptyStateText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:gravity="center"
        android:padding="32dp"
        android:text="@string/sketch_list_empty"
        android:textAppearance="?attr/textAppearanceBodyLarge"
        android:textColor="?attr/colorOnSurfaceVariant"
        android:visibility="gone" />

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/newSketchFab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        android:contentDescription="@string/action_new_sketch"
        android:src="@drawable/ic_add"
        app:backgroundTint="@color/esp_accent"
        app:tint="@color/esp_on_accent" />

</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 6: Replace `layout/item_sketch.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:minHeight="56dp"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:background="?attr/selectableItemBackground"
    android:paddingStart="16dp"
    android:paddingEnd="4dp">

    <ImageView
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:importantForAccessibility="no"
        android:src="@drawable/ic_description"
        app:tint="?attr/colorPrimary" />

    <TextView
        android:id="@+id/sketchNameText"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_weight="1"
        android:fontFamily="@font/jetbrains_mono_regular"
        android:textAppearance="?attr/textAppearanceBodyLarge"
        android:textColor="?attr/colorOnSurface" />

    <ImageButton
        android:id="@+id/sketchOverflowButton"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/action_more_options"
        android:src="@drawable/ic_more_vert" />

</LinearLayout>
```

- [ ] **Step 7: Add row dividers in `SketchListActivity.onCreate`.** Put this
  right after `binding.sketchRecyclerView.adapter = adapter`:

```kotlin
        binding.sketchRecyclerView.addItemDecoration(
            MaterialDividerItemDecoration(this, MaterialDividerItemDecoration.VERTICAL).apply {
                dividerInsetStart = resources.getDimensionPixelSize(R.dimen.sketch_row_divider_inset)
                isLastItemDecorated = false
            }
        )
```

Add the import:

```kotlin
import com.google.android.material.divider.MaterialDividerItemDecoration
```

And create `app/src/main/res/values/dimens.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 16dp row padding + 24dp icon + 16dp gap: the divider starts under the name -->
    <dimen name="sketch_row_divider_inset">56dp</dimen>
</resources>
```

- [ ] **Step 8: Replace `layout/activity_editor.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@color/esp_bar"
        android:theme="@style/ThemeOverlay.EspSketchIDE.Bar"
        app:elevation="0dp"
        app:liftOnScroll="false">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize" />

        <com.google.android.material.tabs.TabLayout
            android:id="@+id/fileTabLayout"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@color/esp_bar"
            app:tabGravity="start"
            app:tabIndicatorColor="@color/esp_accent"
            app:tabIndicatorFullWidth="true"
            app:tabMinWidth="72dp"
            app:tabMode="scrollable"
            app:tabSelectedTextColor="@color/esp_on_bar"
            app:tabTextAppearance="@style/TextAppearance.EspSketchIDE.Tab"
            app:tabTextColor="@color/esp_on_bar_muted" />

        <View
            android:layout_width="match_parent"
            android:layout_height="1dp"
            android:background="@color/esp_divider" />

    </com.google.android.material.appbar.AppBarLayout>

    <io.github.rosemoe.sora.widget.CodeEditor
        android:id="@+id/codeEditor"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

</LinearLayout>
```

- [ ] **Step 9: Replace `menu/editor_toolbar.xml`.** The icons tint
  themselves through `colorControlNormal`, so there's no `iconTint`.

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/action_save"
        android:icon="@drawable/ic_save"
        android:title="@string/action_save"
        app:showAsAction="ifRoom" />
    <item
        android:id="@+id/action_add_file"
        android:icon="@drawable/ic_add"
        android:title="@string/action_add_file"
        app:showAsAction="ifRoom" />
</menu>
```

- [ ] **Step 10: Build and fix leftover references to the removed colors**

Run: `grep -rnE "esp_primary_dark|esp_on_primary|esp_background_(light|dark)|esp_surface_(light|dark)" app/src/main`
Expected: no output. Remove any hit you find.

Run: `./gradlew :app:assembleDebug -q`
Expected: `BUILD SUCCESSFUL`, no resource-linking errors.

- [ ] **Step 11: Check dark mode on the emulator.** Install, open the list,
  open a sketch, and take a screenshot. Light mode can't be selected until
  Task 5 adds Settings, so the light check happens in Task 5 Step 9.
  Expected dark result:
  - charcoal toolbar and tab bar, white title
  - monospace tab labels with an orange indicator
  - a white save/add icon
  - list rows with a teal file icon, monospace name, ⋮ icon, and dividers
  - an orange FAB with a white +

- [ ] **Step 12: Commit**

```bash
git add -A app/src/main/res app/src/main/java/org/espsketchide/app/SketchListActivity.kt
git commit -m "IDE palette with day/night colors, flat bars, Material Symbols icons

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Settings screen

**Files:**
- Create: `app/src/main/java/org/espsketchide/app/settings/SettingsActivity.kt`,
  `app/src/main/res/layout/activity_settings.xml`,
  `app/src/main/res/xml/preferences.xml`,
  `app/src/main/res/values/arrays.xml`
- Modify: `app/build.gradle.kts`, `AndroidManifest.xml`,
  `values/strings.xml`, `menu/sketch_list_toolbar.xml`,
  `menu/editor_toolbar.xml`, `SketchListActivity.kt`, `EditorActivity.kt`

- [ ] **Step 1: Add the dependency.** In `app/build.gradle.kts`
  `dependencies { … }`, after the `documentfile` line:

```kotlin
    implementation("androidx.preference:preference-ktx:1.2.1")
```

- [ ] **Step 2: Add strings** to `values/strings.xml` before `</resources>`,
  and delete the now-unused `action_open_folder` line:

```xml
    <string name="action_settings">Settings</string>
    <string name="action_change_folder">Change sketchbook folder</string>
    <string name="settings_title">Settings</string>
    <string name="settings_category_appearance">Appearance</string>
    <string name="settings_theme">Theme</string>
    <string name="theme_dark">Dark</string>
    <string name="theme_light">Light</string>
    <string name="theme_system">Follow system</string>
    <string name="settings_category_editor">Editor</string>
    <string name="settings_font_size">Font size</string>
    <string name="settings_word_wrap">Word wrap</string>
    <string name="settings_word_wrap_summary">Wrap long lines instead of scrolling sideways</string>
    <string name="settings_category_about">About</string>
    <string name="settings_licenses">Open-source licenses</string>
    <string name="settings_licenses_summary">Third-party code, fonts and icons in this app</string>
```

- [ ] **Step 3: Create `values/arrays.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="theme_entries">
        <item>@string/theme_dark</item>
        <item>@string/theme_light</item>
        <item>@string/theme_system</item>
    </string-array>
    <!-- Must match ThemeMode keys in settings/AppSettings.kt -->
    <string-array name="theme_values" translatable="false">
        <item>dark</item>
        <item>light</item>
        <item>system</item>
    </string-array>
</resources>
```

- [ ] **Step 4: Create `xml/preferences.xml`.** Keys must match the
  `AppSettings.kt` constants.

```xml
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <PreferenceCategory
        app:iconSpaceReserved="false"
        app:title="@string/settings_category_appearance">
        <ListPreference
            app:defaultValue="dark"
            app:entries="@array/theme_entries"
            app:entryValues="@array/theme_values"
            app:iconSpaceReserved="false"
            app:key="theme"
            app:title="@string/settings_theme"
            app:useSimpleSummaryProvider="true" />
    </PreferenceCategory>

    <PreferenceCategory
        app:iconSpaceReserved="false"
        app:title="@string/settings_category_editor">
        <SeekBarPreference
            android:max="24"
            app:defaultValue="14"
            app:iconSpaceReserved="false"
            app:key="editor_font_size"
            app:min="10"
            app:showSeekBarValue="true"
            app:title="@string/settings_font_size" />
        <SwitchPreferenceCompat
            app:defaultValue="false"
            app:iconSpaceReserved="false"
            app:key="word_wrap"
            app:summary="@string/settings_word_wrap_summary"
            app:title="@string/settings_word_wrap" />
    </PreferenceCategory>

    <PreferenceCategory
        app:iconSpaceReserved="false"
        app:title="@string/settings_category_about">
        <Preference
            app:iconSpaceReserved="false"
            app:key="licenses"
            app:summary="@string/settings_licenses_summary"
            app:title="@string/settings_licenses" />
    </PreferenceCategory>
</PreferenceScreen>
```

- [ ] **Step 5: Create `layout/activity_settings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@color/esp_bar"
        android:theme="@style/ThemeOverlay.EspSketchIDE.Bar"
        app:elevation="0dp"
        app:liftOnScroll="false">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:title="@string/settings_title" />

        <View
            android:layout_width="match_parent"
            android:layout_height="1dp"
            android:background="@color/esp_divider" />

    </com.google.android.material.appbar.AppBarLayout>

    <FrameLayout
        android:id="@+id/settingsContainer"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

</LinearLayout>
```

- [ ] **Step 6: Create `settings/SettingsActivity.kt`**

```kotlin
package org.espsketchide.app.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.espsketchide.app.R
import org.espsketchide.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = PREFS_NAME
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<ListPreference>(KEY_THEME)?.setOnPreferenceChangeListener { _, newValue ->
            AppCompatDelegate.setDefaultNightMode(ThemeMode.fromKey(newValue as String).nightMode)
            true
        }
        findPreference<Preference>(KEY_LICENSES)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_licenses)
                .setMessage(licensesText())
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
            true
        }
    }

    /** NOTICES.md first, then every bundled license text. */
    private fun licensesText(): String {
        val assets = requireContext().assets
        val others = assets.list(LICENSES_DIR).orEmpty().filter { it != NOTICES_FILE }.sorted()
        return (listOf(NOTICES_FILE) + others).joinToString("\n\n————————\n\n") { name ->
            assets.open("$LICENSES_DIR/$name").bufferedReader().use { it.readText() }.trim()
        }
    }

    private companion object {
        const val KEY_LICENSES = "licenses"
        const val LICENSES_DIR = "licenses"
        const val NOTICES_FILE = "NOTICES.md"
    }
}
```

- [ ] **Step 7: Register the activity.** In `AndroidManifest.xml`, inside
  `<application>` after the `EditorActivity` entry:

```xml
        <activity
            android:name=".settings.SettingsActivity"
            android:exported="false"
            android:label="@string/settings_title" />
```

- [ ] **Step 8: Add Settings to both toolbars**

Replace `menu/sketch_list_toolbar.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/action_change_folder"
        android:title="@string/action_change_folder"
        app:showAsAction="never" />
    <item
        android:id="@+id/action_settings"
        android:title="@string/action_settings"
        app:showAsAction="never" />
</menu>
```

Append to `menu/editor_toolbar.xml`, before `</menu>`:

```xml
    <item
        android:id="@+id/action_settings"
        android:title="@string/action_settings"
        app:showAsAction="never" />
```

In `SketchListActivity.onOptionsItemSelected`, replace the body with:

```kotlin
        return when (item.itemId) {
            R.id.action_change_folder -> {
                pickRootFolder.launch(null)
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
```

In `EditorActivity.onOptionsItemSelected`, add this branch before `else ->`:

```kotlin
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
```

Imports: add `import org.espsketchide.app.settings.SettingsActivity` to both
files, and `import android.content.Intent` to `EditorActivity.kt`.

- [ ] **Step 9: Build and check on the emulator**

```bash
./gradlew :app:installDebug -q && adb shell am start -n org.espsketchide.app/.SketchListActivity
```

Check each of these:
- ⋮ → Settings opens the screen, and Theme shows "Dark".
- Changing Theme to Light immediately recolors the Settings screen, and Back
  shows a light list.
- Font size and Word wrap persist across reopening Settings.
- Open-source licenses shows a scrollable dialog starting with
  "# Third-party notices".
- Theme set back to Dark keeps the app dark.

Also do the light-mode visual check that Task 4 deferred.

- [ ] **Step 10: Commit**

```bash
git add -A app/build.gradle.kts app/src/main
git commit -m "Add Settings screen: theme, editor font size, word wrap, licenses

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: TextMate highlighting, editor font, and settings in the editor

**Files:**
- Create: `app/src/main/assets/textmate/arduino.tmLanguage.json`,
  `app/src/main/assets/textmate/languages.json`,
  `app/src/main/assets/textmate/themes/ide-dark.json`,
  `app/src/main/assets/textmate/themes/ide-light.json`,
  `app/src/main/java/org/espsketchide/app/editor/EditorLanguages.kt`
- Test: `app/src/androidTest/java/org/espsketchide/app/editor/HighlightingTest.kt`
- Modify: `app/build.gradle.kts`, `EspSketchApp.kt`, `EditorActivity.kt`

- [ ] **Step 1: Swap the language dependency and fix the license comment.**
  In `app/build.gradle.kts`, replace the sora comment block and the two sora
  lines with:

```kotlin
    // Code editor (sora-editor, LGPL-2.1, used unmodified as a library). language-textmate
    // highlights sketches with VS Code's C++ grammar plus our Arduino injection grammar
    // (assets/textmate). See editor/EditorLanguages.kt and assets/licenses/NOTICES.md.
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
    implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.6")
```

- [ ] **Step 2: Write the failing instrumented test**
  `app/src/androidTest/java/org/espsketchide/app/editor/HighlightingTest.kt`

```kotlin
package org.espsketchide.app.editor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighlightingTest {

    private val sketch = listOf(
        "#include <WiFi.h>",
        "uint8_t counter = 0; // count loops",
        "void loop() {",
        "  digitalWrite(LED_BUILTIN, HIGH);",
        "  Serial.println(\"hi\");",
        "}",
    )

    private lateinit var grammar: IGrammar

    @Before
    fun setUp() {
        assertTrue(EditorLanguages.awaitReady(ApplicationProvider.getApplicationContext()))
        grammar = requireNotNull(GrammarRegistry.getInstance().findGrammar(EditorLanguages.ARDUINO_SCOPE))
    }

    /** Scopes of the token covering the first occurrence of [text] on line [lineIndex]. */
    private fun scopesAt(lineIndex: Int, text: String): List<String> {
        var state: IStateStack? = null
        sketch.forEachIndexed { i, line ->
            val result = grammar.tokenizeLine(line, state, null)
            if (i == lineIndex) {
                val column = line.indexOf(text)
                require(column >= 0) { "'$text' not on line $lineIndex" }
                return result.tokens.first { column >= it.startIndex && column < it.endIndex }.scopes
            }
            state = result.ruleStack
        }
        error("line $lineIndex out of range")
    }

    private fun assertScope(lineIndex: Int, text: String, prefix: String) {
        val scopes = scopesAt(lineIndex, text)
        assertTrue("'$text' scopes $scopes lack $prefix", scopes.any { it.startsWith(prefix) })
    }

    @Test
    fun preprocessorDirectiveIsHighlighted() = assertScope(0, "#include", "keyword.control.directive")

    @Test
    fun includePathIsStringNotArduinoClass() {
        assertScope(0, "WiFi.h", "string")
        assertTrue(scopesAt(0, "WiFi.h").none { it.startsWith("support.class.arduino") })
    }

    @Test
    fun fixedWidthTypeIsHighlighted() {
        val scopes = scopesAt(1, "uint8_t")
        assertTrue("uint8_t scopes $scopes", scopes.any { it.startsWith("support.type") || it.startsWith("storage.type") })
    }

    @Test
    fun lineCommentIsHighlighted() = assertScope(1, "// count", "comment")

    @Test
    fun arduinoFunctionInsideFunctionBody() = assertScope(3, "digitalWrite", "support.function.arduino")

    @Test
    fun arduinoConstants() {
        assertScope(3, "LED_BUILTIN", "support.constant.arduino")
        assertScope(3, "HIGH", "support.constant.arduino")
    }

    @Test
    fun arduinoClassAndString() {
        assertScope(4, "Serial", "support.class.arduino")
        assertScope(4, "hi", "string")
    }

    @Test
    fun bothThemesAreRegistered() {
        assertNotNull(ThemeRegistry.getInstance().findThemeByThemeName(EditorLanguages.THEME_DARK))
        assertNotNull(ThemeRegistry.getInstance().findThemeByThemeName(EditorLanguages.THEME_LIGHT))
    }
}
```

- [ ] **Step 3: Run it and confirm it fails.** The emulator must be running
  (`adb devices` shows `emulator-5554`).

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.espsketchide.app.editor.HighlightingTest`
Expected: FAIL at compile time, with `Unresolved reference 'EditorLanguages'`.

- [ ] **Step 4: Create `assets/textmate/languages.json`.** The C++ grammars
  are listed before the Arduino grammar that includes them.

```json
{
  "languages": [
    {
      "grammar": "textmate/cpp/cpp.embedded.macro.tmLanguage.json",
      "name": "cpp-macro",
      "scopeName": "source.cpp.embedded.macro"
    },
    {
      "grammar": "textmate/cpp/cpp.tmLanguage.json",
      "name": "cpp",
      "scopeName": "source.cpp",
      "languageConfiguration": "textmate/cpp/language-configuration.json"
    },
    {
      "grammar": "textmate/arduino.tmLanguage.json",
      "name": "arduino",
      "scopeName": "source.arduino",
      "languageConfiguration": "textmate/cpp/language-configuration.json"
    }
  ]
}
```

- [ ] **Step 5: Create `assets/textmate/arduino.tmLanguage.json`**

```json
{
  "name": "Arduino",
  "scopeName": "source.arduino",
  "fileTypes": ["ino"],
  "comment": "Arduino/ESP names injected into VS Code's C++ grammar. An injection (not top-level patterns) because the C++ grammar re-enters itself via $self inside blocks, so top-level patterns would never apply inside function bodies. 'L:' gives these patterns priority over C++'s own identifier rules; '-string -comment' keeps them out of literals.",
  "patterns": [{ "include": "source.cpp" }],
  "injections": {
    "L:source.arduino -string -comment": {
      "patterns": [
        { "include": "#functions" },
        { "include": "#constants" },
        { "include": "#classes" }
      ]
    }
  },
  "repository": {
    "functions": {
      "match": "\\b(?:pinMode|digitalWrite|digitalRead|analogRead|analogWrite|analogReadResolution|analogWriteResolution|analogWriteRange|delay|delayMicroseconds|millis|micros|tone|noTone|pulseIn|shiftIn|shiftOut|attachInterrupt|detachInterrupt|digitalPinToInterrupt|interrupts|noInterrupts|map|constrain|random|randomSeed|yield|ledcAttach|ledcAttachChannel|ledcWrite|ledcWriteTone|ledcDetach|touchRead|hallRead|temperatureRead|configTime|getLocalTime|esp_deep_sleep_start|esp_sleep_enable_timer_wakeup|esp_sleep_enable_touchpad_wakeup|setup|loop)\\b",
      "name": "support.function.arduino"
    },
    "constants": {
      "match": "\\b(?:HIGH|LOW|INPUT|OUTPUT|INPUT_PULLUP|INPUT_PULLDOWN|LED_BUILTIN|CHANGE|RISING|FALLING|LSBFIRST|MSBFIRST|A0|WL_CONNECTED|WL_DISCONNECTED|WL_IDLE_STATUS|WL_NO_SSID_AVAIL|WL_CONNECT_FAILED|WIFI_STA|WIFI_AP|WIFI_AP_STA|WIFI_OFF|HTTP_CODE_OK|HTTP_GET|HTTP_POST|WAKEUP_PULLUP|RF_DEFAULT|SERIAL_8N1)\\b",
      "name": "support.constant.arduino"
    },
    "classes": {
      "match": "\\b(?:Serial|Serial1|Serial2|WiFi|WiFiClient|WiFiClientSecure|WiFiServer|WiFiUDP|WebServer|ESP8266WebServer|HTTPClient|String|Preferences|EEPROM|ESP|Wire|SPI|Ticker)\\b",
      "name": "support.class.arduino"
    }
  }
}
```

- [ ] **Step 6: Create `assets/textmate/themes/ide-dark.json`**

```json
{
  "name": "ide-dark",
  "type": "dark",
  "colors": {
    "editor.background": "#1E1F22",
    "editor.foreground": "#D4D4D4",
    "editor.lineHighlightBackground": "#26282E",
    "editor.selectionBackground": "#264F78",
    "editorCursor.foreground": "#AEAFAD",
    "editorLineNumber.foreground": "#5A5D63",
    "editorLineNumber.activeForeground": "#A1A3AB",
    "editorIndentGuide.background": "#313438",
    "editorIndentGuide.activeBackground": "#50535A",
    "editorWhitespace.foreground": "#3B3E43",
    "completionWindowBackground": "#2B2D30",
    "completionWindowBackgroundCurrent": "#2E436E"
  },
  "tokenColors": [
    { "settings": { "foreground": "#D4D4D4", "background": "#1E1F22" } },
    { "scope": ["comment", "punctuation.definition.comment"], "settings": { "foreground": "#6A9955", "fontStyle": "italic" } },
    { "scope": ["string", "punctuation.definition.string"], "settings": { "foreground": "#CE9178" } },
    { "scope": "constant.character.escape", "settings": { "foreground": "#D7BA7D" } },
    { "scope": "constant.numeric", "settings": { "foreground": "#B5CEA8" } },
    { "scope": ["keyword", "storage", "storage.type", "storage.modifier", "constant.language", "keyword.operator.new", "keyword.operator.delete", "keyword.operator.sizeof"], "settings": { "foreground": "#569CD6" } },
    { "scope": ["keyword.control", "keyword.control.directive", "punctuation.definition.directive"], "settings": { "foreground": "#C586C0" } },
    { "scope": "keyword.operator", "settings": { "foreground": "#D4D4D4" } },
    { "scope": ["entity.name.function", "support.function"], "settings": { "foreground": "#DCDCAA" } },
    { "scope": ["entity.name.type", "entity.name.class", "entity.name.namespace", "support.type", "support.class"], "settings": { "foreground": "#4EC9B0" } },
    { "scope": ["entity.name.function.preprocessor", "entity.name.macro"], "settings": { "foreground": "#569CD6" } },
    { "scope": "variable.parameter", "settings": { "foreground": "#9CDCFE" } },
    { "scope": ["support.function.arduino", "support.class.arduino"], "settings": { "foreground": "#FFA657", "fontStyle": "bold" } },
    { "scope": "support.constant.arduino", "settings": { "foreground": "#4FC1FF" } }
  ]
}
```

- [ ] **Step 7: Create `assets/textmate/themes/ide-light.json`**

```json
{
  "name": "ide-light",
  "type": "light",
  "colors": {
    "editor.background": "#FFFFFF",
    "editor.foreground": "#1F2328",
    "editor.lineHighlightBackground": "#F2F3F5",
    "editor.selectionBackground": "#ADD6FF",
    "editorCursor.foreground": "#000000",
    "editorLineNumber.foreground": "#A0A1A7",
    "editorLineNumber.activeForeground": "#3B3B3B",
    "editorIndentGuide.background": "#E3E4E8",
    "editorIndentGuide.activeBackground": "#C5C7CC",
    "editorWhitespace.foreground": "#D3D3D3",
    "completionWindowBackground": "#F7F8FA",
    "completionWindowBackgroundCurrent": "#DDE7FF"
  },
  "tokenColors": [
    { "settings": { "foreground": "#1F2328", "background": "#FFFFFF" } },
    { "scope": ["comment", "punctuation.definition.comment"], "settings": { "foreground": "#008000", "fontStyle": "italic" } },
    { "scope": ["string", "punctuation.definition.string"], "settings": { "foreground": "#A31515" } },
    { "scope": "constant.character.escape", "settings": { "foreground": "#EE0000" } },
    { "scope": "constant.numeric", "settings": { "foreground": "#098658" } },
    { "scope": ["keyword", "storage", "storage.type", "storage.modifier", "constant.language", "keyword.operator.new", "keyword.operator.delete", "keyword.operator.sizeof"], "settings": { "foreground": "#0000FF" } },
    { "scope": ["keyword.control", "keyword.control.directive", "punctuation.definition.directive"], "settings": { "foreground": "#AF00DB" } },
    { "scope": "keyword.operator", "settings": { "foreground": "#1F2328" } },
    { "scope": ["entity.name.function", "support.function"], "settings": { "foreground": "#795E26" } },
    { "scope": ["entity.name.type", "entity.name.class", "entity.name.namespace", "support.type", "support.class"], "settings": { "foreground": "#267F99" } },
    { "scope": ["entity.name.function.preprocessor", "entity.name.macro"], "settings": { "foreground": "#0000FF" } },
    { "scope": "variable.parameter", "settings": { "foreground": "#001080" } },
    { "scope": ["support.function.arduino", "support.class.arduino"], "settings": { "foreground": "#C0560A", "fontStyle": "bold" } },
    { "scope": "support.constant.arduino", "settings": { "foreground": "#0070C1" } }
  ]
}
```

- [ ] **Step 8: Create `editor/EditorLanguages.kt`**

```kotlin
package org.espsketchide.app.editor

import android.content.Context
import android.util.Log
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * One-time TextMate setup (grammars and themes from assets/textmate) plus the language
 * and color scheme for each editor file. Loading is slow enough (large C++ grammar) to
 * run in the background: [EspSketchApp][org.espsketchide.app.EspSketchApp] starts it,
 * and the editor waits on it with [awaitReady].
 */
object EditorLanguages {

    const val ARDUINO_SCOPE = "source.arduino"
    const val THEME_DARK = "ide-dark"
    const val THEME_LIGHT = "ide-light"

    private const val TAG = "EditorLanguages"
    private const val TAB_SIZE = 2
    private val HIGHLIGHTED_EXTENSIONS = setOf("ino", "h", "hpp", "c", "cpp", "cc")

    private var loading: Future<*>? = null

    /** Starts loading in the background. Safe to call repeatedly; later calls reuse the first load. */
    @Synchronized
    fun init(context: Context): Future<*> {
        loading?.let { return it }
        val appContext = context.applicationContext
        val executor = Executors.newSingleThreadExecutor()
        return executor.submit { load(appContext) }.also {
            executor.shutdown()
            loading = it
        }
    }

    /** Blocks until loading finishes. Returns false if it failed; the editor then runs without highlighting. */
    fun awaitReady(context: Context): Boolean = try {
        init(context).get()
        true
    } catch (e: ExecutionException) {
        Log.e(TAG, "TextMate setup failed", e.cause)
        false
    }

    /** Highlighted Arduino/C++ language for sketch sources, plain text otherwise. Call after [awaitReady] returned true. */
    fun languageFor(fileName: String): Language {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in HIGHLIGHTED_EXTENSIONS) return EmptyLanguage()
        return TextMateLanguage.create(ARDUINO_SCOPE, true).apply {
            tabSize = TAB_SIZE
            useTab(false)
        }
    }

    /** Switches the shared theme registry to the dark or light theme and returns a scheme that follows it. */
    fun colorScheme(isDark: Boolean): EditorColorScheme {
        val themes = ThemeRegistry.getInstance()
        themes.setTheme(if (isDark) THEME_DARK else THEME_LIGHT)
        return TextMateColorScheme.create(themes)
    }

    private fun load(context: Context) {
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.assets))
        val themes = ThemeRegistry.getInstance()
        loadTheme(themes, THEME_DARK, isDark = true)
        loadTheme(themes, THEME_LIGHT, isDark = false)
        themes.setTheme(THEME_DARK)
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
    }

    private fun loadTheme(themes: ThemeRegistry, name: String, isDark: Boolean) {
        val path = "textmate/themes/$name.json"
        val stream = requireNotNull(FileProviderRegistry.getInstance().tryGetInputStream(path)) { "Missing $path" }
        val source = IThemeSource.fromInputStream(stream, path, Charsets.UTF_8)
        themes.loadTheme(ThemeModel(source, name).apply { this.isDark = isDark })
    }
}
```

- [ ] **Step 9: Start loading from the Application.** In `EspSketchApp.onCreate`,
  add after the `setDefaultNightMode` line:

```kotlin
        EditorLanguages.init(this)
```

And add `import org.espsketchide.app.editor.EditorLanguages`.

- [ ] **Step 10: Run the instrumented test and confirm it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.espsketchide.app.editor.HighlightingTest`
Expected: `BUILD SUCCESSFUL`, 8 tests passed.

**If the build fails** with `2 files found with path '<P>'` (duplicate
META-INF resources from the TextMate dependencies), add this inside
`android { }` in `app/build.gradle.kts` with the exact reported path, then
rerun:

```kotlin
    packaging {
        resources.excludes += "<P>"
    }
```

**If only `fixedWidthTypeIsHighlighted` fails,** read the printed scopes. If
`uint8_t` is tokenized with a scope that isn't `support.type*` or
`storage.type*` (for example `entity.name.type`), then extend the assertion to
that exact prefix **and** make sure the themes color that scope. The test's
purpose is "gets a type color", not a specific scope name.

**If several tests fail with empty scopes,** the C++ grammar isn't compatible
with sora's engine. Apply the spec fallback: vendor VS Code's
`extensions/cpp/syntaxes/c.tmLanguage.json` at the same commit (ask the user
before downloading), add it to `languages.json` with `scopeName` `source.c`,
and change the Arduino grammar's top-level include from `source.cpp` to
`source.c`. The injection selector stays as it is.

- [ ] **Step 11: Use it in `EditorActivity`.** Make these edits:

Imports: remove `JavaLanguage`, and add:

```kotlin
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import io.github.rosemoe.sora.lang.EmptyLanguage
import org.espsketchide.app.editor.EditorLanguages
import org.espsketchide.app.settings.AppSettings
```

Fields, next to `repository`:

```kotlin
    private lateinit var settings: AppSettings
    private var highlightingAvailable = false
```

In `onCreate`, right after `repository = SketchRepository(this)`:

```kotlin
        settings = AppSettings(this)
```

Replace `setupEditor()` with:

```kotlin
    private fun setupEditor() {
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        highlightingAvailable = EditorLanguages.awaitReady(this)
        binding.codeEditor.colorScheme = when {
            highlightingAvailable -> EditorLanguages.colorScheme(isDarkMode)
            isDarkMode -> SchemeDarcula()
            else -> SchemeGitHub()
        }
        val mono = ResourcesCompat.getFont(this, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
        binding.codeEditor.setTypefaceText(mono)
        binding.codeEditor.setTypefaceLineNumber(mono)
        binding.codeEditor.setTabWidth(2)
    }

    /** Font size and word wrap can change in Settings while this screen is in the back stack. */
    private fun applyEditorSettings() {
        binding.codeEditor.setTextSize(settings.editorFontSize.toFloat())
        binding.codeEditor.setWordwrap(settings.wordWrap)
    }
```

Replace `loadFile` with:

```kotlin
    private fun loadFile(file: SketchFile) {
        currentFile = file
        binding.codeEditor.setEditorLanguage(
            if (highlightingAvailable) EditorLanguages.languageFor(file.name) else EmptyLanguage()
        )
        binding.codeEditor.setText(repository.readFile(file.uri))
    }
```

Add `onResume` and `onDestroy` next to `onPause`:

```kotlin
    override fun onResume() {
        super.onResume()
        applyEditorSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.codeEditor.release()
    }
```

`onCreate` can `finish()` before `binding` is used, but `binding` is always
assigned first, so `release()` in `onDestroy` is safe.

- [ ] **Step 12: Build, install, and check on the emulator**

```bash
./gradlew :app:installDebug -q
adb shell am force-stop org.espsketchide.app && adb shell am start -n org.espsketchide.app/.SketchListActivity
```

Open a sketch and type the sample from the test into its `.ino` file (or push
it with `adb shell` into `/sdcard/Documents/Sketches/<Sketch>/<Sketch>.ino`
and reopen). Expected in dark mode:
- `#include` purple, `<WiFi.h>` salmon
- `uint8_t` teal, comment green italic
- `digitalWrite`/`Serial` orange bold, `HIGH`/`LED_BUILTIN` light blue
- JetBrains Mono text

Switch Settings → Theme → Light, reopen the sketch, and confirm the light
colors. Then check that Font size 18 and Word wrap on apply after pressing
Back from Settings into the open editor.

Also confirm there's no ANR on a warm open (`adb logcat -d | grep "ANR in"`
is empty), and note the `Displayed …EditorActivity` time from logcat.

- [ ] **Step 13: Commit**

```bash
git add -A app/build.gradle.kts app/src/main app/src/androidTest
git commit -m "Arduino/C++ highlighting via TextMate, JetBrains Mono, editor settings

Replaces the Java-grammar stand-in with VS Code's C++ grammar plus an Arduino
injection grammar, adds dark/light IDE editor themes, and applies font size
and word wrap from Settings.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Docs

**Files:**
- Modify: `README.md`, `CLAUDE.md`

- [ ] **Step 1: README.md changes**

Replace the editor bullet under "What works today" (lines 16–17):

```markdown
- ✅ Multi-file editor with tabs per sketch, Arduino/C++ syntax highlighting,
  JetBrains Mono, and save-on-switch/save-on-exit.
- ✅ Dark-by-default IDE theme (Light / Dark / Follow system) and a Settings
  screen for theme, editor font size, and word wrap.
```

Replace the whole "About the syntax highlighting" section body (lines 24–31):

```markdown
The editor uses [sora-editor](https://github.com/Rosemoe/sora-editor)'s
TextMate engine with Visual Studio Code's C/C++ grammar, so preprocessor
lines (`#include`, `#define`), fixed-width types (`uint8_t`), strings,
comments, and numbers are highlighted like in a desktop editor. A small
Arduino grammar (`app/src/main/assets/textmate/arduino.tmLanguage.json`)
is injected on top to color the Arduino/ESP API: functions such as
`pinMode` and `digitalWrite`, constants such as `HIGH` and `LED_BUILTIN`,
and classes such as `Serial` and `WiFi`.
```

Replace the "License" section body:

```markdown
MIT — see [LICENSE](LICENSE). Bundled third-party grammars, fonts, icons, and
libraries are listed in
[app/src/main/assets/licenses/NOTICES.md](app/src/main/assets/licenses/NOTICES.md)
and in the app under Settings → Open-source licenses.
```

Leave the roadmap checkboxes unchanged. M8 also covers examples and
releases, so it isn't complete yet.

- [ ] **Step 2: CLAUDE.md changes.** In `## Architecture`:

Replace the **Syntax highlighting** bullet with:

```markdown
- **Syntax highlighting:** sora-editor's `language-textmate` with VS Code's C++ grammar (`assets/textmate/cpp/`, vendored, MIT) plus our `assets/textmate/arduino.tmLanguage.json`, which *injects* Arduino/ESP names (`L:source.arduino -string -comment`). It must be an injection: the C++ grammar re-enters itself via `$self` inside blocks, so top-level patterns never reach function bodies. Themes are our own `assets/textmate/themes/ide-{dark,light}.json`. `editor/EditorLanguages` loads all of it once on a background thread (started in `EspSketchApp`); `EditorActivity` blocks on `awaitReady()` and falls back to plain text if loading failed. `HighlightingTest` (androidTest) pins the expected scopes.
```

Add after the **Editor** bullet:

```markdown
- **Settings and theme:** `settings/AppSettings` (typed reads) over the same `esp_sketch_ide` prefs file; `SettingsActivity` (androidx Preference, `res/xml/preferences.xml`) writes them. `EspSketchApp` applies the theme with `AppCompatDelegate.setDefaultNightMode` (default **dark**). Colors come from `values/colors.xml` + `values-night/colors.xml`; app bars use `ThemeOverlay.EspSketchIDE.Bar`.
- **Third-party assets:** everything vendored is listed in `app/src/main/assets/licenses/NOTICES.md` with its license text alongside; add to it whenever you vendor something. sora-editor is LGPL-2.1 (used unmodified).
```

In `## Build`, replace the "no unit or instrumented tests yet" paragraph with:

```markdown
JVM unit tests live in `app/src/test` and instrumented tests in `app/src/androidTest` (run on a device/emulator; locally there's an `EspSketchIDE_API34` AVD). Run one JVM test with `./gradlew :app:testDebugUnitTest --tests "org.espsketchide.app.settings.AppSettingsTest"`, one instrumented class with `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.espsketchide.app.editor.HighlightingTest`.
```

- [ ] **Step 3: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "Document TextMate highlighting, settings, and tests

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Full verification

- [ ] **Step 1: JVM unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 2: Instrumented tests**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [ ] **Step 3: Lint**

Run: `./gradlew :app:lint` then `grep -c "Error:" app/build/reports/lint-results-debug.txt`
Expected: `0`. New warnings are fine only if they're dependency-version or
unused-resource notices. List them in the summary.

- [ ] **Step 4: Regression pass on the emulator.** Check create, rename,
  add `config.h`, edit, switch tabs, and delete, with disk checks via
  `adb shell ls -R /sdcard/Documents/Sketches`. That's the same flow verified
  before Phase 1.

- [ ] **Step 5: Screenshots.** Capture the sketch list and the editor with the
  sample sketch, in dark and light (Settings → Theme). Send them to the user.

- [ ] **Step 6: APK size.** Run `stat -f %z app/build/outputs/apk/debug/app-debug.apk`
  and compare with the pre-Phase-1 debug APK: **6,841,488 bytes** (built from
  `ff11dae`). The spec expects roughly +3–4 MB. Report the difference.
