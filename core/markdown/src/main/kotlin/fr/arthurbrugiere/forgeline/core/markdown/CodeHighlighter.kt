package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

/** Syntax highlighting for the file viewer. Pure and thread-safe: run it off the main thread. */
object CodeHighlighter {
    /** Past this size highlighting takes too long for the "instant" budget; show plain text. */
    private const val MAX_HIGHLIGHT_LENGTH = 200_000

    private val languages = mapOf(
        "kt" to SyntaxLanguage.KOTLIN, "kts" to SyntaxLanguage.KOTLIN,
        "java" to SyntaxLanguage.JAVA,
        "py" to SyntaxLanguage.PYTHON,
        "js" to SyntaxLanguage.JAVASCRIPT, "mjs" to SyntaxLanguage.JAVASCRIPT, "cjs" to SyntaxLanguage.JAVASCRIPT, "jsx" to SyntaxLanguage.JAVASCRIPT,
        "ts" to SyntaxLanguage.TYPESCRIPT, "tsx" to SyntaxLanguage.TYPESCRIPT, "mts" to SyntaxLanguage.TYPESCRIPT,
        "rs" to SyntaxLanguage.RUST,
        "go" to SyntaxLanguage.GO,
        "c" to SyntaxLanguage.C, "h" to SyntaxLanguage.C,
        "cc" to SyntaxLanguage.CPP, "cpp" to SyntaxLanguage.CPP, "cxx" to SyntaxLanguage.CPP, "hpp" to SyntaxLanguage.CPP,
        "cs" to SyntaxLanguage.CSHARP,
        "swift" to SyntaxLanguage.SWIFT,
        "rb" to SyntaxLanguage.RUBY,
        "php" to SyntaxLanguage.PHP,
        "sh" to SyntaxLanguage.SHELL, "bash" to SyntaxLanguage.SHELL, "zsh" to SyntaxLanguage.SHELL, "fish" to SyntaxLanguage.SHELL,
        "dart" to SyntaxLanguage.DART,
        "coffee" to SyntaxLanguage.COFFEESCRIPT,
        "pl" to SyntaxLanguage.PERL, "pm" to SyntaxLanguage.PERL,
    )

    private fun language(fileName: String): SyntaxLanguage? = languages[fileName.substringAfterLast('.', "").lowercase()]

    fun languageName(fileName: String): String? = language(fileName)?.name

    fun isMarkdown(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown", "mkd")

    fun isBinary(text: String): Boolean = text.take(8_000).contains('\u0000')

    fun highlight(code: String, fileName: String, darkTheme: Boolean): AnnotatedString {
        val language = language(fileName)
        if (language == null || code.length > MAX_HIGHLIGHT_LENGTH) return AnnotatedString(code)
        val highlights = Highlights.Builder()
            .code(code)
            .language(language)
            .theme(SyntaxThemes.default(darkTheme))
            .build()
            .getHighlights()
        return buildAnnotatedString {
            append(code)
            for (highlight in highlights) {
                val location = highlight.location
                if (location.start < 0 || location.end > code.length || location.start >= location.end) continue
                when (highlight) {
                    is ColorHighlight -> addStyle(SpanStyle(color = Color(0xFF000000 or highlight.rgb.toLong())), location.start, location.end)
                    is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), location.start, location.end)
                }
            }
        }
    }

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
