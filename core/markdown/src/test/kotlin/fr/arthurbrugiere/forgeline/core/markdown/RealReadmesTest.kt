package fr.arthurbrugiere.forgeline.core.markdown

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** READMEs of trending repos captured on 2026-09-26: the messy input the app really gets. */
class RealReadmesTest {
    private val names = listOf(
        "paperclipai_paperclip",
        "vectorize-io_hindsight",
        "anthropics_claude-code-action",
        "NVIDIA_Model-Optimizer",
    )

    private fun readme(name: String) = requireNotNull(javaClass.getResource("/readmes/$name.md")).readText()

    private fun prepared(name: String, darkTheme: Boolean = false): Pair<String, String> {
        val (owner, repo) = name.split('_', limit = 2)
        val context = ReadmeContext(
            rawBaseUrl = "https://raw.githubusercontent.com/$owner/$repo/main/",
            blobBaseUrl = "https://github.com/$owner/$repo/blob/main/",
        )
        val input = readme(name)
        return input to ReadmePreprocessor.prepare(input, context, darkTheme)
    }

    private val fencedBlock = Regex("""(?ms)^ {0,3}(```|~~~).*?^ {0,3}\1[^\n]*$""")

    private fun String.withoutCode() = replace(fencedBlock, "").replace(Regex("""(`+)(?:(?!\1).)+?\1"""), "")

    @Test
    fun no_renderable_html_is_left_outside_code() {
        for (name in names) {
            val prose = prepared(name).second.withoutCode()
            for (tag in listOf("<img", "<picture", "<source", "<p ", "<p>", "<div", "<details", "<summary", "<a ", "<br", "<h1", "<h2", "<h3")) {
                assertWithMessage("$name still contains $tag").that(prose.contains(tag, ignoreCase = true)).isFalse()
            }
        }
    }

    @Test
    fun code_blocks_are_byte_identical() {
        for (name in names) {
            val (input, output) = prepared(name)
            assertWithMessage(name).that(fencedBlock.findAll(output).map { it.value }.toList())
                .isEqualTo(fencedBlock.findAll(input).map { it.value }.toList())
        }
    }

    @Test
    fun every_image_has_an_absolute_url() {
        val image = Regex("""!\[[^\]]*]\(([^)\s]+)""")
        for (name in names) for (dark in listOf(false, true)) {
            val prose = prepared(name, dark).second.withoutCode()
            for (match in image.findAll(prose)) {
                assertWithMessage("$name: ${match.value}").that(match.groupValues[1]).startsWith("http")
            }
        }
    }

    @Test
    fun converted_html_never_turns_into_indented_code() {
        val indentedImage = Regex("""(?m)^( {4,}|\t)\[?!\[""")
        for (name in names) {
            assertWithMessage(name).that(indentedImage.containsMatchIn(prepared(name).second.withoutCode())).isFalse()
        }
    }
}
