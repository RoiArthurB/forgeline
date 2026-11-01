package fr.arthurbrugiere.forgeline.core.markdown

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class CodeHighlighterTest {
    @Test
    fun detects_languages_from_file_names() {
        assertThat(CodeHighlighter.languageName("Main.kt")).isEqualTo("KOTLIN")
        assertThat(CodeHighlighter.languageName("build.gradle.kts")).isEqualTo("KOTLIN")
        assertThat(CodeHighlighter.languageName("app.tsx")).isEqualTo("TYPESCRIPT")
        assertThat(CodeHighlighter.languageName("script.sh")).isEqualTo("SHELL")
        assertThat(CodeHighlighter.languageName("lib.rs")).isEqualTo("RUST")
        assertThat(CodeHighlighter.languageName("notes.txt")).isNull()
        assertThat(CodeHighlighter.languageName("LICENSE")).isNull()
        assertThat(CodeHighlighter.languageName(".gitignore")).isNull()
    }

    // Regression: only 17 languages were known, so JSON, YAML, XML, Gradle, Makefiles and the like showed no color at all.
    @Test
    fun files_of_other_languages_get_the_generic_colors() {
        listOf("package.json", "ci.yml", "AndroidManifest.xml", "libs.versions.toml", "style.css", "query.sql", "model.gaml").forEach {
            assertWithMessage(it).that(CodeHighlighter.languageName(it)).isEqualTo("DEFAULT")
        }
        val json = "{\n  \"name\": \"forgeline\",\n  \"version\": 42\n}\n"
        assertThat(CodeHighlighter.highlight(json, "package.json", darkTheme = false).spanStyles).isNotEmpty()
    }

    @Test
    fun well_known_names_and_more_extensions_get_their_language() {
        assertThat(CodeHighlighter.languageName("Makefile")).isEqualTo("SHELL")
        assertThat(CodeHighlighter.languageName("docker/Dockerfile")).isEqualTo("SHELL")
        assertThat(CodeHighlighter.languageName("Dockerfile.release")).isEqualTo("SHELL")
        assertThat(CodeHighlighter.languageName("Gemfile")).isEqualTo("RUBY")
        assertThat(CodeHighlighter.languageName("build.gradle")).isEqualTo("JAVA")
        assertThat(CodeHighlighter.languageName("stubs.pyi")).isEqualTo("PYTHON")
        assertThat(CodeHighlighter.languageName("lib.hh")).isEqualTo("CPP")
    }

    @Test
    fun scripts_without_an_extension_are_told_by_their_first_line() {
        assertThat(CodeHighlighter.languageName("gradlew", "#!/bin/sh\n\necho hi\n")).isEqualTo("SHELL")
        assertThat(CodeHighlighter.languageName("tool", "#!/usr/bin/env python3\nprint(1)\n")).isEqualTo("PYTHON")
        assertThat(CodeHighlighter.languageName("run", "#!/usr/bin/env -S node --flag\n")).isEqualTo("JAVASCRIPT")
        assertThat(CodeHighlighter.languageName("deploy", "#!/bin/bash -e\n")).isEqualTo("SHELL")
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
    fun keywords_get_colored_and_the_text_is_untouched() {
        val code = "fun main() {\n    val answer = 42\n}\n"

        val highlighted = CodeHighlighter.highlight(code, "Main.kt", darkTheme = false)

        assertThat(highlighted.text).isEqualTo(code)
        val funStyles = highlighted.spanStyles.filter { it.start == 0 && it.end == 3 }
        assertThat(funStyles.map { it.item.color }.distinct()).hasSize(1)
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
}
