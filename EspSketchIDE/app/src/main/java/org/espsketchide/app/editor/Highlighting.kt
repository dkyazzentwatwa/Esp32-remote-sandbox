package org.espsketchide.app.editor

import android.content.Context
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

private const val CPP_SCOPE = "source.cpp"
private const val LIGHT_THEME = "textmate/themes/espsketch-light.json"
private const val DARK_THEME = "textmate/themes/espsketch-dark.json"

/** Kinds of highlighting the editor offers; picked from the file extension. */
enum class SourceKind { CPP, PLAIN }

fun sourceKindFor(fileName: String): SourceKind =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "ino", "h", "hpp", "c", "cpp", "cc" -> SourceKind.CPP
        else -> SourceKind.PLAIN
    }

/**
 * TextMate C++ highlighting (VS Code's grammar, see assets/textmate/cpp/NOTICE.md).
 * The grammar is large, so [load] should run off the main thread; it's safe to call repeatedly.
 */
object Highlighting {

    @Volatile
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
        val themes = ThemeRegistry.getInstance()
        for ((path, dark) in listOf(LIGHT_THEME to false, DARK_THEME to true)) {
            val stream = FileProviderRegistry.getInstance().tryGetInputStream(path)
            val model = ThemeModel(IThemeSource.fromInputStream(stream, path, null), path)
            model.isDark = dark
            themes.loadTheme(model)
        }
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        loaded = true
    }

    fun colorScheme(dark: Boolean): EditorColorScheme {
        val themes = ThemeRegistry.getInstance()
        themes.setTheme(if (dark) DARK_THEME else LIGHT_THEME)
        return TextMateColorScheme.create(themes)
    }

    fun language(kind: SourceKind): Language = when (kind) {
        SourceKind.CPP -> TextMateLanguage.create(CPP_SCOPE, true)
        SourceKind.PLAIN -> EmptyLanguage()
    }
}
