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
import dev.snipme.highlights.model.SyntaxTheme

/** Syntax highlighting for the file viewer. Pure and thread-safe: run it off the main thread. */
object CodeHighlighter {
    /** Past this size highlighting takes too long for the "instant" budget; show plain text. */
    private const val MAX_HIGHLIGHT_LENGTH = 200_000

    private val languages = mapOf(
        "kt" to SyntaxLanguage.KOTLIN, "kts" to SyntaxLanguage.KOTLIN,
        "java" to SyntaxLanguage.JAVA, "groovy" to SyntaxLanguage.JAVA, "gradle" to SyntaxLanguage.JAVA,
        "py" to SyntaxLanguage.PYTHON, "pyi" to SyntaxLanguage.PYTHON, "pyw" to SyntaxLanguage.PYTHON,
        "js" to SyntaxLanguage.JAVASCRIPT, "mjs" to SyntaxLanguage.JAVASCRIPT, "cjs" to SyntaxLanguage.JAVASCRIPT, "jsx" to SyntaxLanguage.JAVASCRIPT,
        "ts" to SyntaxLanguage.TYPESCRIPT, "tsx" to SyntaxLanguage.TYPESCRIPT, "mts" to SyntaxLanguage.TYPESCRIPT,
        "rs" to SyntaxLanguage.RUST,
        "go" to SyntaxLanguage.GO,
        "c" to SyntaxLanguage.C, "h" to SyntaxLanguage.C,
        "cc" to SyntaxLanguage.CPP, "cpp" to SyntaxLanguage.CPP, "cxx" to SyntaxLanguage.CPP, "c++" to SyntaxLanguage.CPP,
        "hpp" to SyntaxLanguage.CPP, "hh" to SyntaxLanguage.CPP, "hxx" to SyntaxLanguage.CPP, "ino" to SyntaxLanguage.CPP,
        "cs" to SyntaxLanguage.CSHARP,
        "swift" to SyntaxLanguage.SWIFT,
        "rb" to SyntaxLanguage.RUBY, "rake" to SyntaxLanguage.RUBY, "gemspec" to SyntaxLanguage.RUBY,
        "php" to SyntaxLanguage.PHP,
        "sh" to SyntaxLanguage.SHELL, "bash" to SyntaxLanguage.SHELL, "zsh" to SyntaxLanguage.SHELL, "fish" to SyntaxLanguage.SHELL,
        "ksh" to SyntaxLanguage.SHELL, "bats" to SyntaxLanguage.SHELL, "mk" to SyntaxLanguage.SHELL,
        "dart" to SyntaxLanguage.DART,
        "coffee" to SyntaxLanguage.COFFEESCRIPT,
        "pl" to SyntaxLanguage.PERL, "pm" to SyntaxLanguage.PERL,
    )

    /** Files known by their whole name, most without an extension. */
    private val namedFiles = mapOf(
        "makefile" to SyntaxLanguage.SHELL, "gnumakefile" to SyntaxLanguage.SHELL,
        "dockerfile" to SyntaxLanguage.SHELL, "containerfile" to SyntaxLanguage.SHELL, "pkgbuild" to SyntaxLanguage.SHELL,
        ".bashrc" to SyntaxLanguage.SHELL, ".bash_profile" to SyntaxLanguage.SHELL, ".zshrc" to SyntaxLanguage.SHELL, ".profile" to SyntaxLanguage.SHELL,
        "gemfile" to SyntaxLanguage.RUBY, "rakefile" to SyntaxLanguage.RUBY, "podfile" to SyntaxLanguage.RUBY,
        "fastfile" to SyntaxLanguage.RUBY, "brewfile" to SyntaxLanguage.RUBY, "vagrantfile" to SyntaxLanguage.RUBY,
        "jenkinsfile" to SyntaxLanguage.JAVA,
    )

    /** Scripts without an extension say what runs them on their first line, as `#!/usr/bin/env python3`. */
    private val interpreters = listOf(
        "python" to SyntaxLanguage.PYTHON, "node" to SyntaxLanguage.JAVASCRIPT, "ruby" to SyntaxLanguage.RUBY,
        "perl" to SyntaxLanguage.PERL, "php" to SyntaxLanguage.PHP, "bash" to SyntaxLanguage.SHELL,
        "zsh" to SyntaxLanguage.SHELL, "fish" to SyntaxLanguage.SHELL, "sh" to SyntaxLanguage.SHELL,
    )

    /** Prose and tables: strings, numbers and comments mean nothing there, so they are left plain. */
    private val plainExtensions = setOf("txt", "text", "log", "csv", "tsv", "rst", "adoc", "asciidoc", "org")

    // Token colors from the Soft palette, each readable at 4.5:1 on its ground (CodeContrastTest holds them to it).
    private val SoftLightSyntax = SyntaxTheme(
        key = "soft-light",
        code = 0x16181A,
        keyword = 0x8F5600,
        string = 0x2E7550,
        literal = 0x1F5FB8,
        comment = 0x606065,
        metadata = 0x7A45B0,
        multilineComment = 0x606065,
        punctuation = 0x3D3A4F,
        mark = 0x16181A,
    )
    private val SoftDarkSyntax = SyntaxTheme(
        key = "soft-dark",
        code = 0xEDEDF0,
        keyword = 0xFFC062,
        string = 0x8FD6A8,
        literal = 0x8CB8FF,
        comment = 0x9C9BA3,
        metadata = 0xC9A7F5,
        multilineComment = 0x9C9BA3,
        punctuation = 0xC9C4D6,
        mark = 0xEDEDF0,
    )

    /**
     * The language to color [fileName] as, [code] telling a script by its first line. Files of a language the
     * highlighter has no grammar for (JSON, YAML, XML, TOML, CSS, SQL…) still get its generic one, which colors
     * strings, numbers and comments. Prose, and files nothing is known of, stay plain.
     */
    private fun language(fileName: String, code: String? = null): SyntaxLanguage? {
        val name = fileName.substringAfterLast('/').lowercase()
        namedFiles[name]?.let { return it }
        if (name.startsWith("dockerfile.") || name.endsWith(".dockerfile")) return SyntaxLanguage.SHELL
        val extension = name.substringAfterLast('.', "").takeIf { name.lastIndexOf('.') > 0 }
        if (extension != null) {
            languages[extension]?.let { return it }
            if (extension in plainExtensions) return null
            return SyntaxLanguage.DEFAULT
        }
        return code?.let(::shebangLanguage)
    }

    private fun shebangLanguage(code: String): SyntaxLanguage? {
        if (!code.startsWith("#!")) return null
        val words = code.substringBefore('\n').removePrefix("#!").trim().split(' ', '\t').filter { it.isNotEmpty() }
        val command = words.firstOrNull()?.substringAfterLast('/') ?: return null
        // `#!/usr/bin/env python3` names the program after env, maybe past flags such as -S.
        val program = if (command == "env") words.drop(1).firstOrNull { !it.startsWith("-") } ?: return null else command
        return interpreters.firstOrNull { (prefix, _) -> program.startsWith(prefix) }?.second
    }

    fun languageName(fileName: String, code: String? = null): String? = language(fileName, code)?.name

    fun isMarkdown(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown", "mkd")

    fun isBinary(text: String): Boolean = text.take(8_000).contains('\u0000')

    fun highlight(code: String, fileName: String, darkTheme: Boolean): AnnotatedString {
        val language = language(fileName, code)
        if (language == null || code.length > MAX_HIGHLIGHT_LENGTH) return AnnotatedString(code)
        val highlights = Highlights.Builder()
            .code(code)
            .language(language)
            .theme(if (darkTheme) SoftDarkSyntax else SoftLightSyntax)
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
