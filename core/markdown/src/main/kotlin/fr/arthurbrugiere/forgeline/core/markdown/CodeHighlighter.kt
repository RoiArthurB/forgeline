package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.EncodedTokenAttributes
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry
import java.time.Duration

/**
 * Syntax highlighting for the file viewer and Markdown's code blocks, with the TextMate grammars VS Code colors code
 * with (see [TextMateLanguages]) and the Soft palette's themes (`textmate/themes`). Pure and thread-safe: run it off
 * the main thread.
 */
object CodeHighlighter {
    /** Past this size highlighting takes too long for the "instant" budget; show plain text. */
    private const val MAX_HIGHLIGHT_LENGTH = 200_000

    /** A line a grammar can't finish in this time (a minified bundle) is left plain from there on. */
    private val LINE_TIME_LIMIT = Duration.ofSeconds(1)

    private val languages get() = TextMateLanguages.bundled

    /** One registry per theme: a grammar's tokens carry the colors of the theme it was loaded with. */
    private class Engine(theme: String) {
        private val registry = Registry(
            object : IRegistryOptions {
                override fun getGrammarSource(scopeName: String): IGrammarSource? = languages.grammars[scopeName]?.let { grammar ->
                    resource("/textmate/${grammar.file}")?.let { IGrammarSource.fromInputStream(it, grammar.file, Charsets.UTF_8) }
                }

                override fun getInjections(scopeName: String): Collection<String> = languages.injectionsInto(scopeName)
            },
        ).apply {
            setTheme(IThemeSource.fromInputStream(checkNotNull(resource("/textmate/themes/$theme.json")), "$theme.json", Charsets.UTF_8))
        }
        private val colors: List<Color?> = registry.colorMap.map { hex -> hex?.removePrefix("#")?.take(6)?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } }

        /** The theme's own ink, which tm4e lists first: the text is drawn in it already, so it needs no span. */
        private val defaultColor = colors.firstOrNull()
        private val grammars = HashMap<String, IGrammar?>()

        /**
         * Grammars compile their rules as they meet them, so one file is colored at a time per theme. A grammar that
         * fails (a pattern its regex engine can't read) leaves its files plain rather than taking the screen down.
         */
        @Synchronized
        fun highlight(code: String, scopeName: String): AnnotatedString = tryHighlight(code, scopeName) ?: AnnotatedString(code)

        /** [code] colored with [scopeName]'s grammar, or null when that grammar can't be loaded or fails. */
        @Synchronized
        fun tryHighlight(code: String, scopeName: String): AnnotatedString? {
            val grammar = grammars.getOrPut(scopeName) { runCatching { registry.loadGrammar(scopeName) }.getOrNull() } ?: return null
            return runCatching { tokenize(code, grammar) }.getOrElse {
                grammars[scopeName] = null
                null
            }
        }

        private fun tokenize(code: String, grammar: IGrammar): AnnotatedString = buildAnnotatedString {
            append(code)
            var state: IStateStack? = null
            var lineStart = 0
            while (lineStart <= code.length) {
                val lineEnd = code.indexOf('\n', lineStart).let { if (it < 0) code.length else it }
                val line = code.substring(lineStart, lineEnd).removeSuffix("\r")
                val result = grammar.tokenizeLine2(line, state, LINE_TIME_LIMIT)
                val tokens = result.tokens
                for (i in tokens.indices step 2) {
                    val start = tokens[i]
                    val end = if (i + 2 < tokens.size) tokens[i + 2] else line.length
                    if (start >= end) continue
                    style(tokens[i + 1])?.let { addStyle(it, lineStart + start, lineStart + end) }
                }
                state = result.ruleStack
                lineStart = lineEnd + 1
            }
        }

        private fun style(metadata: Int): SpanStyle? {
            val color = colors.getOrNull(EncodedTokenAttributes.getForeground(metadata))?.takeIf { it != defaultColor }
            val fontStyle = EncodedTokenAttributes.getFontStyle(metadata).takeIf { it > 0 } ?: 0
            if (color == null && fontStyle == 0) return null
            val decorations = listOfNotNull(
                TextDecoration.Underline.takeIf { fontStyle and UNDERLINE != 0 },
                TextDecoration.LineThrough.takeIf { fontStyle and STRIKETHROUGH != 0 },
            )
            return SpanStyle(
                color = color ?: Color.Unspecified,
                fontWeight = FontWeight.Bold.takeIf { fontStyle and BOLD != 0 },
                fontStyle = FontStyle.Italic.takeIf { fontStyle and ITALIC != 0 },
                textDecoration = decorations.takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
            )
        }

        private companion object {
            // TextMate's font style bits.
            const val ITALIC = 1
            const val BOLD = 2
            const val UNDERLINE = 4
            const val STRIKETHROUGH = 8
        }
    }

    private val light by lazy { Engine("soft-light") }
    private val dark by lazy { Engine("soft-dark") }

    private fun resource(path: String) = CodeHighlighter::class.java.getResourceAsStream(path)

    /** The language [path] (a file name or its path in the repository) is colored as, or null when it stays plain. */
    fun languageName(path: String, code: String? = null): String? = languages.forFile(path, code)?.id

    /** Whether [path] is Markdown, shown as a document rather than as code. */
    fun isMarkdown(path: String): Boolean = languages.forFile(path)?.scopeName?.startsWith("text.html.markdown") == true

    fun isBinary(text: String): Boolean = text.take(8_000).contains('\u0000')

    /** [code] from the file at [path], colored for its language; plain when none is known. */
    fun highlight(code: String, path: String, darkTheme: Boolean): AnnotatedString =
        highlight(code, languages.forFile(path, code), darkTheme)

    /** A Markdown code block, colored for the language its fence names (```kotlin); plain without one. */
    fun highlightFence(code: String, fenceLanguage: String?, darkTheme: Boolean): AnnotatedString =
        highlight(code, fenceLanguage?.let(languages::forFence), darkTheme)

    private fun highlight(code: String, language: TextMateLanguages.Language?, darkTheme: Boolean): AnnotatedString {
        if (language == null || code.length > MAX_HIGHLIGHT_LENGTH) return AnnotatedString(code)
        return (if (darkTheme) dark else light).highlight(code, language.scopeName)
    }

    /** Whether [scopeName]'s grammar loads and colors [code] in both themes without failing; for tests. */
    internal fun grammarWorks(scopeName: String, code: String): Boolean =
        light.tryHighlight(code, scopeName) != null && dark.tryHighlight(code, scopeName) != null

    /** One entry per line, styles kept; used to render big files lazily with line numbers. */
    fun lines(text: AnnotatedString): List<AnnotatedString> {
        val result = mutableListOf<AnnotatedString>()
        var start = 0
        while (start <= text.length) {
            val end = text.text.indexOf('\n', start).let { if (it < 0) text.length else it }
            if (start == text.length && end == text.length) break
            result += text.subSequence(start, end)
            start = end + 1
        }
        return result
    }
}
