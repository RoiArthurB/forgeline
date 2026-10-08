package fr.arthurbrugiere.forgeline.core.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadmePreprocessorTest {
    private val context = ReadmeContext(
        rawBaseUrl = "https://raw.githubusercontent.com/octo/repo/main/",
        blobBaseUrl = "https://github.com/octo/repo/blob/main/",
    )

    private fun prepare(markdown: String, darkTheme: Boolean = false) =
        ReadmePreprocessor.prepare(markdown, context, darkTheme)

    @Test
    fun plain_markdown_is_left_alone() {
        val markdown = "# Title\n\nSome *text* with a [link](https://example.com).\n"
        assertThat(prepare(markdown)).isEqualTo(markdown)
    }

    @Test
    fun html_images_become_markdown_images() {
        assertThat(prepare("""<img src="https://example.com/logo.png" alt="Logo" width="200">"""))
            .isEqualTo("![Logo](https://example.com/logo.png)")
    }

    @Test
    fun linked_images_keep_their_link() {
        assertThat(prepare("""<a href="https://ci.example.com"><img src="https://img.shields.io/badge/ci-passing-green" alt="CI"></a>"""))
            .isEqualTo("[![CI](https://img.shields.io/badge/ci-passing-green)](https://ci.example.com)")
    }

    @Test
    fun centered_blocks_are_unwrapped_into_paragraphs() {
        val html = """
            <p align="center">
              <img src="https://example.com/a.png" alt="A">
            </p>
            Text after.
        """.trimIndent()

        assertThat(prepare(html).trim()).isEqualTo("![A](https://example.com/a.png)\n\nText after.")
    }

    @Test
    fun picture_elements_pick_the_variant_matching_the_theme() {
        val html = """<picture><source media="(prefers-color-scheme: dark)" srcset="https://example.com/dark.png"><img src="https://example.com/light.png" alt="Banner"></picture>"""

        assertThat(prepare(html, darkTheme = false)).isEqualTo("![Banner](https://example.com/light.png)")
        assertThat(prepare(html, darkTheme = true)).isEqualTo("![Banner](https://example.com/dark.png)")
    }

    @Test
    fun html_headings_line_breaks_and_emphasis_are_converted() {
        assertThat(prepare("<h1 align=\"center\">Forgeline</h1>")).isEqualTo("# Forgeline")
        assertThat(prepare("<h3>Install</h3>")).isEqualTo("### Install")
        assertThat(prepare("one<br>two<br/>three")).isEqualTo("one  \ntwo  \nthree")
        assertThat(prepare("<b>bold</b> <strong>strong</strong> <em>em</em> <code>x()</code> <kbd>Ctrl</kbd>"))
            .isEqualTo("**bold** **strong** *em* `x()` `Ctrl`")
    }

    @Test
    fun details_are_shown_expanded_with_their_summary() {
        val html = "<details>\n<summary>Advanced</summary>\n\nHidden text.\n</details>"

        assertThat(prepare(html).trim()).isEqualTo("**Advanced**\n\nHidden text.")
    }

    @Test
    fun comments_and_unknown_tags_are_dropped_but_their_text_kept() {
        assertThat(prepare("<!-- badges -->\n<div id=\"x\"><span>Kept</span></div>").trim()).isEqualTo("Kept")
    }

    @Test
    fun code_is_never_touched() {
        val markdown = "Use `<br>` here.\n\n```html\n<img src=\"a.png\">\n<!-- keep -->\n```\n"

        assertThat(prepare(markdown)).isEqualTo(markdown)
    }

    @Test
    fun relative_images_load_from_raw_files() {
        assertThat(prepare("![Shot](docs/screen.png)"))
            .isEqualTo("![Shot](https://raw.githubusercontent.com/octo/repo/main/docs/screen.png)")
        assertThat(prepare("""<img src="./assets/logo.svg" alt="Logo">"""))
            .isEqualTo("![Logo](https://raw.githubusercontent.com/octo/repo/main/assets/logo.svg)")
        assertThat(prepare("![Root](/img/a.png)"))
            .isEqualTo("![Root](https://raw.githubusercontent.com/octo/repo/main/img/a.png)")
    }

    @Test
    fun relative_links_point_at_the_repo_files() {
        assertThat(prepare("See [the guide](docs/GUIDE.md)."))
            .isEqualTo("See [the guide](https://github.com/octo/repo/blob/main/docs/GUIDE.md).")
        assertThat(prepare("[License]: LICENSE"))
            .isEqualTo("[License]: https://github.com/octo/repo/blob/main/LICENSE")
    }

    @Test
    fun absolute_anchor_and_mail_links_are_kept() {
        val markdown = "[a](https://x.dev) [b](#usage) [c](mailto:me@x.dev)"
        assertThat(prepare(markdown)).isEqualTo(markdown)
    }

    @Test
    fun blob_image_urls_on_github_are_turned_into_raw_urls() {
        assertThat(prepare("![Demo](https://github.com/octo/repo/blob/main/demo.gif?raw=true)"))
            .isEqualTo("![Demo](https://raw.githubusercontent.com/octo/repo/main/demo.gif)")
    }

    @Test
    fun html_entities_in_urls_are_decoded() {
        assertThat(prepare("""<img src="https://img.shields.io/x?a=1&amp;b=2" alt="x">"""))
            .isEqualTo("![x](https://img.shields.io/x?a=1&b=2)")
    }

    @Test
    fun readmes_in_subdirectories_resolve_relative_to_their_folder() {
        val nested = context.copy(directory = "docs/")

        assertThat(ReadmePreprocessor.prepare("![a](img/a.png) [b](../LICENSE)", nested, darkTheme = false))
            .isEqualTo(
                "![a](https://raw.githubusercontent.com/octo/repo/main/docs/img/a.png) " +
                    "[b](https://github.com/octo/repo/blob/main/LICENSE)",
            )
    }

    @Test
    fun videos_become_links_since_they_cannot_play_inline() {
        assertThat(prepare("""<video src="https://github.com/user-attachments/assets/abc" width="600" controls></video>""").trim())
            .isEqualTo("[▶ Watch video](https://github.com/user-attachments/assets/abc)")
    }

    @Test
    fun typographic_entities_are_decoded_but_escaping_entities_kept() {
        assertThat(prepare("A &middot; B &mdash; C&nbsp;D &#169; &#x2192; &lt;tag&gt;"))
            .isEqualTo("A · B — C\u00A0D © → &lt;tag&gt;")
    }

    @Test
    fun standalone_line_breaks_become_paragraph_breaks() {
        assertThat(prepare("Intro\n\n<br/>\n\n# Title")).isEqualTo("Intro\n\n# Title")
    }

    // Issue #11: a conversation named by its number was left as plain text.
    private val github = ReferenceLinks(forgeUrl = "https://github.com", repo = "octo/repo", issuePath = "/issues/")
    private val gitlab = ReferenceLinks(forgeUrl = "https://gitlab.com", repo = "group/sub/tool", issuePath = "/-/issues/", mergeRequestPath = "/-/merge_requests/")

    private fun linked(text: String, references: ReferenceLinks? = github) =
        ReadmePreprocessor.prepare(text, ReadmeContext("https://raw.example/", "https://blob.example/", references = references), darkTheme = false)

    @Test
    fun a_conversation_named_by_its_number_becomes_a_link_to_it() {
        assertThat(linked("Like #1, see #204.")).isEqualTo("Like [#1](https://github.com/octo/repo/issues/1), see [#204](https://github.com/octo/repo/issues/204).")
        assertThat(linked("#7 at the start\nand (#8) in brackets"))
            .isEqualTo("[#7](https://github.com/octo/repo/issues/7) at the start\nand ([#8](https://github.com/octo/repo/issues/8)) in brackets")
    }

    @Test
    fun a_conversation_of_another_repository_is_linked_there() {
        assertThat(linked("Same as vercel/next.js#99839.")).isEqualTo("Same as [vercel/next.js#99839](https://github.com/vercel/next.js/issues/99839).")
    }

    @Test
    fun where_merge_requests_are_numbered_apart_each_mark_leads_to_its_own() {
        assertThat(linked("Fixes #7 with !7.", gitlab))
            .isEqualTo("Fixes [#7](https://gitlab.com/group/sub/tool/-/issues/7) with [!7](https://gitlab.com/group/sub/tool/-/merge_requests/7).")
        assertThat(linked("See other/tool!3", gitlab)).isEqualTo("See [other/tool!3](https://gitlab.com/other/tool/-/merge_requests/3)")
        // Elsewhere an exclamation mark before a number is only that.
        assertThat(linked("Wow!7 times")).isEqualTo("Wow!7 times")
    }

    @Test
    fun what_only_looks_like_a_reference_is_left_alone() {
        // A heading, a number with a leading zero, a word glued to it, an address's anchor, an entity.
        for (text in listOf("# 12 steps", "Colour #000", "issue#12", "abc#12", "https://example.com/page#12", "#12abc", "#", "# ")) {
            assertThat(linked(text)).isEqualTo(text)
        }
        // A `#` written as an entity was written not to be a reference: it reads as one, and leads nowhere.
        assertThat(linked("&#35;12")).isEqualTo("#12")
    }

    @Test
    fun a_reference_already_in_a_link_or_in_code_is_not_linked_again() {
        assertThat(linked("[see #12](https://example.com/x)")).isEqualTo("[see #12](https://example.com/x)")
        assertThat(linked("![shot #12](https://example.com/x.png)")).isEqualTo("![shot #12](https://example.com/x.png)")
        assertThat(linked("<https://example.com/#12>")).isEqualTo("<https://example.com/#12>")
        assertThat(linked("Run `fix #12` then #13")).isEqualTo("Run `fix #12` then [#13](https://github.com/octo/repo/issues/13)")
        assertThat(linked("```\n#12\n```\n")).isEqualTo("```\n#12\n```\n")
    }

    @Test
    fun a_readme_s_numbers_are_not_references() {
        // Forges link them in conversations, not in files.
        assertThat(linked("Step #1 of #2", references = null)).isEqualTo("Step #1 of #2")
    }

    @Test
    fun a_reference_beside_html_and_links_is_still_linked() {
        assertThat(linked("<b>Fixed</b> by #12, see [docs](guide.md)"))
            .isEqualTo("**Fixed** by [#12](https://github.com/octo/repo/issues/12), see [docs](https://blob.example/guide.md)")
    }
}
