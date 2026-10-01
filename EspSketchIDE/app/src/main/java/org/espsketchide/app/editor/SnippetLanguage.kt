package org.espsketchide.app.editor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleSnippetCompletionItem
import io.github.rosemoe.sora.lang.completion.SnippetDescription
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference

/**
 * Wraps the TextMate language and puts parameter-filling Arduino snippets ([ArduinoSnippets])
 * ahead of its keyword and identifier completions. Everything else is delegated unchanged.
 */
class SnippetLanguage(
    val base: Language,
    val snippets: List<ArduinoSnippet>,
) : Language by base {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        // Typing a number (e.g. a pin) shouldn't pop up names that merely contain that digit.
        val prefix = CompletionHelper.computePrefix(content, position) { it.isLetterOrDigit() || it == '_' }
        if (prefix.firstOrNull()?.isDigit() == true) return
        val snippetItems = snippetItems(content, position)
        if (snippetItems.isEmpty()) {
            base.requireAutoComplete(content, position, publisher, extraArguments)
            return
        }
        // Collect the TextMate items separately so names a snippet covers (e.g. "digitalWrite",
        // already used in the file) aren't listed twice, and snippets come first.
        val captured = CompletionPublisher(Handler(Looper.getMainLooper()), {}, base.interruptionLevel)
        captured.setUpdateThreshold(Int.MAX_VALUE)
        base.requireAutoComplete(content, position, captured, extraArguments)
        captured.updateList(true)
        val covered = snippetItems.map { it.label.toString() }.toSet()
        publisher.addItems(snippetItems + captured.items.filter { it.label.toString() !in covered })
    }

    private fun snippetItems(content: ContentReference, position: CharPosition): List<CompletionItem> {
        val prefix = CompletionHelper.computePrefix(content, position) { it.isLetterOrDigit() || it == '_' }
        if (prefix.isEmpty()) return emptyList()
        val line = content.getLine(position.line)
        val start = position.column - prefix.length
        // "Serial.pr": the object before the dot narrows the matches to its members.
        val objectName = if (start > 0 && line[start - 1] == '.') {
            line.substring(0, start - 1).takeLastWhile { it.isLetterOrDigit() || it == '_' }.ifEmpty { null }
        } else {
            null
        }
        return ArduinoSnippets.match(snippets, objectName, prefix).map { match ->
            SimpleSnippetCompletionItem(
                match.snippet.label,
                match.snippet.detail,
                SnippetDescription(prefix.length, CodeSnippetParser.parse(match.insert), true),
            ).kind(CompletionItemKind.Snippet)
        }
    }
}
