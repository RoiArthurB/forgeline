package fr.arthurbrugiere.forgeline.core.markdown

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.testing.PerformanceRecorder
import org.junit.Test

/**
 * Performance test suite measuring Markdown preprocessing and AST parsing timings.
 *
 * Provides measurable benchmarks for short issue/PR comments and full repository READMEs,
 * demonstrating the performance impact and speedup of AST caching.
 */
class MarkdownPerformanceTest {
    private val recorder = PerformanceRecorder(warmupIterations = 3, iterations = 20)

    private val commentMarkdown = """
        Fixes #42. As discussed in [RFC-108](https://github.com/octo/repo/issues/108):
        
        - Refactored `DispatchSheet` to support dynamic input controls
        - Verified that **empty choices** fall back to default
        
        ```kotlin
        val state = rememberReadmeState(markdown, context, darkTheme)
        ```
        
        CC @octocat for review!
    """.trimIndent()

    private val context = ReadmeContext(
        rawBaseUrl = "https://raw.githubusercontent.com/octo/repo/main/",
        blobBaseUrl = "https://github.com/octo/repo/blob/main/",
    )

    private fun loadResourceReadme(name: String): String =
        requireNotNull(javaClass.getResource("/readmes/$name.md")).readText()

    @Test
    fun records_comment_markdown_parsing_timing() {
        val result = recorder.measure("Comment Markdown Preprocess + Parse") {
            val prepared = ReadmePreprocessor.prepare(commentMarkdown, context, darkTheme = false)
            val state = parseForgeMarkdown(prepared)
            assertThat(state).isNotNull()
        }
        println(result.formatSummary())
    }

    @Test
    fun records_full_readme_parsing_timing() {
        val readme = loadResourceReadme("paperclipai_paperclip")
        val result = recorder.measure("Full README Preprocess + Parse", notes = mapOf("size_chars" to "${readme.length}")) {
            val prepared = ReadmePreprocessor.prepare(readme, context, darkTheme = false)
            val state = parseForgeMarkdown(prepared)
            assertThat(state).isNotNull()
        }
        println(result.formatSummary())
    }

    @Test
    fun measures_ast_caching_speedup_factor() {
        val readme = loadResourceReadme("anthropics_claude-code-action")

        // Cold parse (parsing from scratch every time)
        val coldResult = recorder.measure("Cold README Parse (Repeated AST generation)") {
            val prepared = ReadmePreprocessor.prepare(readme, context, darkTheme = false)
            val state = parseForgeMarkdown(prepared)
            assertThat(state).isNotNull()
        }
        println(coldResult.formatSummary())

        // What a comment scrolling back into view costs: a lookup in the cache the screens use.
        val key = MarkdownCacheKey(readme, context, darkTheme = false)
        MarkdownAstCache.put(key, parseForgeMarkdown(ReadmePreprocessor.prepare(readme, context, darkTheme = false)))

        val cachedResult = recorder.measure("Cached README Lookup") {
            assertThat(MarkdownAstCache.get(key)).isNotNull()
        }
        println(cachedResult.formatSummary())

        val comparison = cachedResult.compareWithBaseline(coldResult)
        println("Performance Impact: ${comparison.formatSummary()}")
        assertThat(comparison.speedupFactor).isAtLeast(10.0)
    }
}
