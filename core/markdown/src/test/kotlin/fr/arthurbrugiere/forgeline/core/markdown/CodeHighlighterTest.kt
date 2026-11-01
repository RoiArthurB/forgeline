package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// tm4e logs through android.util.Log, which a plain JVM test only has as a stub that throws: Robolectric gives the real one.
@RunWith(RobolectricTestRunner::class)
class CodeHighlighterTest {
    @Test
    fun detects_languages_from_file_names() {
        mapOf(
            "Main.kt" to "kotlin",
            "build.gradle.kts" to "kotlin",
            "app.tsx" to "typescriptreact",
            "script.sh" to "shellscript",
            "lib.rs" to "rust",
            "a.py" to "python",
            "notes.txt" to null,
            "LICENSE" to null,
        ).forEach { (name, language) -> assertWithMessage(name).that(CodeHighlighter.languageName(name)).isEqualTo(language) }
    }

    // Regression: only 17 languages were known, so JSON, YAML, XML, Gradle, Makefiles and the like showed no color at all.
    @Test
    fun the_grammars_index_knows_far_more_than_a_handful_of_languages() {
        mapOf(
            "package.json" to "json",
            "tsconfig.json" to "jsonc",
            "ci.yml" to "yaml",
            "AndroidManifest.xml" to "xml",
            "libs.versions.toml" to "toml",
            "style.css" to "css",
            "query.sql" to "sql",
            "build.gradle" to "groovy",
            "index.html" to "html",
            "Makefile" to "makefile",
            "docker/Dockerfile" to "dockerfile",
            "Gemfile" to "ruby",
            ".bashrc" to "shellscript",
            ".gitignore" to "ignore",
        ).forEach { (name, language) -> assertWithMessage(name).that(CodeHighlighter.languageName(name)).isEqualTo(language) }
    }

    @Test
    fun scripts_without_an_extension_are_told_by_their_first_line() {
        assertThat(CodeHighlighter.languageName("gradlew", "#!/bin/sh\n\necho hi\n")).isEqualTo("shellscript")
        assertThat(CodeHighlighter.languageName("tool", "#!/usr/bin/env python3\nprint(1)\n")).isEqualTo("python")
        assertThat(CodeHighlighter.languageName("run", "#!/usr/bin/env node\n")).isEqualTo("javascript")
        assertThat(CodeHighlighter.languageName("README", "Just words\n")).isNull()
    }

    @Test
    fun recognizes_markdown_files() {
        assertThat(CodeHighlighter.isMarkdown("README.md")).isTrue()
        assertThat(CodeHighlighter.isMarkdown("docs/Guide.MARKDOWN")).isTrue()
        assertThat(CodeHighlighter.isMarkdown("Main.kt")).isFalse()
    }

    @Test
    fun recognizes_binary_content() {
        assertThat(CodeHighlighter.isBinary("PNG\u0000\u0001")).isTrue()
        assertThat(CodeHighlighter.isBinary("fun main() {}\n")).isFalse()
    }

    @Test
    fun tokens_take_the_theme_colors_and_the_text_is_untouched() {
        val code = "fun main() {\n    val answer = 42\n}\n"

        val light = CodeHighlighter.highlight(code, "Main.kt", darkTheme = false)
        val dark = CodeHighlighter.highlight(code, "Main.kt", darkTheme = true)

        assertThat(light.text).isEqualTo(code)
        assertThat(light.colorOf(code, "fun")).isEqualTo(Color(0xFF8F5600))
        assertThat(light.colorOf(code, "42")).isEqualTo(Color(0xFF1F5FB8))
        assertThat(dark.colorOf(code, "fun")).isEqualTo(Color(0xFFFFC062))
        // What is in the theme's own ink needs no span: the text is drawn in it already.
        assertThat(light.spanStyles.none { code.substring(it.start, it.end).contains("main") }).isTrue()
    }

    @Test
    fun json_keys_and_values_are_told_apart() {
        val json = "{\n  \"name\": \"forgeline\",\n  \"version\": 42\n}\n"

        val highlighted = CodeHighlighter.highlight(json, "package.json", darkTheme = false)

        assertThat(highlighted.colorOf(json, "\"name\"")).isNotEqualTo(highlighted.colorOf(json, "\"forgeline\""))
        assertThat(highlighted.colorOf(json, "42")).isNotNull()
    }

    @Test
    fun markdown_code_fences_are_colored_by_the_language_they_name() {
        listOf("kotlin" to "val x = \"s\"", "kt" to "val x = 1", "js" to "const x = 1", "sh" to "echo \"hi\"", "Python" to "def f(): pass").forEach { (fence, code) ->
            assertWithMessage(fence).that(CodeHighlighter.highlightFence(code, fence, darkTheme = false).spanStyles).isNotEmpty()
        }
        assertThat(CodeHighlighter.highlightFence("val x = 1", null, darkTheme = false).spanStyles).isEmpty()
        assertThat(CodeHighlighter.highlightFence("val x = 1", "not-a-language", darkTheme = false).spanStyles).isEmpty()
    }

    @Test
    fun unknown_languages_stay_plain() {
        assertThat(CodeHighlighter.highlight("Copyright 2026 \"Arthur\"", "LICENSE", darkTheme = false).spanStyles).isEmpty()
    }

    @Test
    fun very_large_files_skip_highlighting() {
        val big = "val x = 1\n".repeat(30_000)

        assertThat(CodeHighlighter.highlight(big, "Big.kt", darkTheme = false).spanStyles).isEmpty()
    }

    @Test
    fun windows_line_ends_keep_tokens_in_place() {
        val code = "fun a()\r\nval b = 1\r\n"

        assertThat(CodeHighlighter.highlight(code, "A.kt", darkTheme = false).colorOf(code, "val")).isEqualTo(Color(0xFF8F5600))
    }

    @Test
    fun splits_into_lines_keeping_styles_per_line() {
        val highlighted = CodeHighlighter.highlight("fun a()\nval b = 1", "A.kt", darkTheme = true)

        val lines = CodeHighlighter.lines(highlighted)

        assertThat(lines.map { it.text }).containsExactly("fun a()", "val b = 1").inOrder()
        assertThat(lines[1].spanStyles.any { it.start == 0 && it.end == 3 }).isTrue()
    }

    @Test
    fun a_trailing_newline_does_not_add_an_empty_last_line() {
        assertThat(CodeHighlighter.lines(CodeHighlighter.highlight("a\nb\n", "x.txt", false)).map { it.text })
            .containsExactly("a", "b").inOrder()
    }

    private fun androidx.compose.ui.text.AnnotatedString.colorOf(code: String, token: String): Color? {
        val start = code.indexOf(token)
        return spanStyles.firstOrNull { it.start == start && it.end == start + token.length }?.item?.color
    }
}
