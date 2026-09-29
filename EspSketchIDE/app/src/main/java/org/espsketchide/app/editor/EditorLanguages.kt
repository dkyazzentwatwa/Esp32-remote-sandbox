package org.espsketchide.app.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import org.espsketchide.app.settings.Board
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * One-time TextMate setup (grammars and themes from assets/textmate) plus the language
 * and color scheme for each editor file. Loading takes seconds on slow devices (large C++
 * grammar), so it runs in the background: [EspSketchApp][org.espsketchide.app.EspSketchApp]
 * starts it, and the editor switches highlighting on via [whenReady] without blocking.
 */
object EditorLanguages {

    const val ARDUINO_SCOPE = "source.arduino"
    const val THEME_DARK = "ide-dark"
    const val THEME_LIGHT = "ide-light"

    private const val TAG = "EditorLanguages"
    private const val TAB_SIZE = 2
    private val HIGHLIGHTED_EXTENSIONS = setOf("ino", "h", "hpp", "c", "cpp", "cc")

    private var loading: Future<*>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /**
     * Calls [callback] on the main thread with the result of [awaitReady], without blocking it.
     * When loading has already finished (the usual case) and this is called on the main thread,
     * the callback runs immediately, so the editor's first frame is already highlighted.
     */
    fun whenReady(context: Context, callback: (Boolean) -> Unit) {
        if (init(context).isDone && Looper.myLooper() == Looper.getMainLooper()) {
            callback(awaitReady(context))
            return
        }
        Thread({
            val ok = awaitReady(context)
            mainHandler.post { callback(ok) }
        }, "EditorLanguagesWait").start()
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
    fun languageFor(fileName: String, board: Board): Language {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in HIGHLIGHTED_EXTENSIONS) return EmptyLanguage()
        return TextMateLanguage.create(ARDUINO_SCOPE, true).apply {
            tabSize = TAB_SIZE
            useTab(false)
            setCompleterKeywords(ArduinoApi.keywordsFor(board).toTypedArray())
        }
    }

    /** Switches the shared theme registry to the dark or light theme and returns a scheme that follows it. */
    fun colorScheme(isDark: Boolean): EditorColorScheme {
        val themes = ThemeRegistry.getInstance()
        themes.setTheme(if (isDark) THEME_DARK else THEME_LIGHT)
        return TextMateColorScheme.create(themes)
    }

    private fun load(context: Context) {
        val start = SystemClock.elapsedRealtime()
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.assets))
        val themes = ThemeRegistry.getInstance()
        loadTheme(themes, THEME_DARK, isDark = true)
        loadTheme(themes, THEME_LIGHT, isDark = false)
        themes.setTheme(THEME_DARK)
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        warmUp()
        Log.i(TAG, "TextMate ready in ${SystemClock.elapsedRealtime() - start} ms")
    }

    /**
     * Tokenizes a small sketch once so tm4e compiles the grammar's rules (including a deep copy
     * of the ~1 MB C++ grammar it includes) here, in the background. Otherwise that happens on
     * the editor's analyzer thread while it holds a lock that CodeEditor.setText needs, which
     * froze the UI for seconds (ANR) on the first file opened. The registry shares the compiled
     * grammar with every TextMateLanguage created later.
     */
    private fun warmUp() {
        val grammar = GrammarRegistry.getInstance().findGrammar(ARDUINO_SCOPE) ?: return
        var state: IStateStack? = null
        for (line in WARM_UP_SKETCH) {
            state = grammar.tokenizeLine(line, state, null).ruleStack
        }
    }

    private val WARM_UP_SKETCH = listOf(
        "#include <WiFi.h>",
        "#define LED_PIN 2",
        "/* block */ // line",
        "static const uint8_t pins[] = {1, 2};",
        "class Blinker { public: explicit Blinker(int p) : pin(p) {} private: int pin; };",
        "void setup() {",
        "  Serial.begin(115200);",
        "  for (int i = 0; i < 3; i++) { pinMode(i, OUTPUT); }",
        "  if (WiFi.status() == WL_CONNECTED) { Serial.printf(\"%d\\n\", millis()); }",
        "  while (false) { delay(1); }",
        "}",
    )

    private fun loadTheme(themes: ThemeRegistry, name: String, isDark: Boolean) {
        val path = "textmate/themes/$name.json"
        val stream = requireNotNull(FileProviderRegistry.getInstance().tryGetInputStream(path)) { "Missing $path" }
        val source = IThemeSource.fromInputStream(stream, path, Charsets.UTF_8)
        themes.loadTheme(ThemeModel(source, name).apply { this.isDark = isDark })
    }
}
