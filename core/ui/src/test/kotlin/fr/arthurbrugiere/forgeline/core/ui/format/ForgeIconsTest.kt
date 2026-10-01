package fr.arthurbrugiere.forgeline.core.ui.format

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.ui.R
import org.junit.Test

class ForgeIconsTest {
    private val selfHosted = ForgeInstance(ForgeType.FORGEJO, "git.example.org")

    @Test
    fun each_forge_has_its_own_logo_and_any_other_server_forgejos() {
        assertThat(ForgeInstance.GitHub.iconRes).isEqualTo(R.drawable.ic_forge_github)
        assertThat(ForgeInstance.Codeberg.iconRes).isEqualTo(R.drawable.ic_forge_codeberg)
        assertThat(ForgeInstance.GitLab.iconRes).isEqualTo(R.drawable.ic_forge_gitlab)
        assertThat(selfHosted.iconRes).isEqualTo(R.drawable.ic_forge_forgejo)
    }

    @Test
    fun every_forge_is_named_next_to_its_logo() {
        // Rows and chips that mix forges must not lean on a 14dp logo alone: "GitHub", "Codeberg", or the server's host.
        assertThat(ForgeInstance.GitHub.displayName).isEqualTo("GitHub")
        assertThat(ForgeInstance.Codeberg.displayName).isEqualTo("Codeberg")
        assertThat(selfHosted.displayName).isEqualTo("git.example.org")
    }

    @Test
    fun in_a_line_of_text_the_logo_is_followed_by_the_forges_name() {
        val github = buildAnnotatedString { appendForge(ForgeInstance.GitHub); append(" · 2h") }
        val codeberg = buildAnnotatedString { appendForge(ForgeInstance.Codeberg); append(" · 2h") }
        val host = buildAnnotatedString { appendForge(selfHosted) }

        assertThat(github.text).contains("GitHub · 2h")
        assertThat(codeberg.text).contains("Codeberg · 2h")
        assertThat(host.text).endsWith("git.example.org")
    }

    @Test
    fun the_logo_and_its_name_never_wrap_apart() {
        val text = buildAnnotatedString { appendForge(ForgeInstance.GitHub) }

        // A plain space here let a narrow line end on the logo and start the next one with "GitHub".
        assertThat(text.text).isEqualTo("\u2060\u00A0GitHub")
    }

    @Test
    fun the_inline_logo_is_silent_because_the_name_is_written_out() {
        val text = buildAnnotatedString {
            appendForge(ForgeInstance.Codeberg)
            append(" · 2h")
        }

        // The logo's alternate text must not repeat the name a screen reader reads right after it.
        val icon = text.getStringAnnotations(tag = "androidx.compose.foundation.text.inlineContent", start = 0, end = text.length).single()
        assertThat(text.text.substring(icon.start, icon.end)).doesNotContain("Codeberg")
        assertThat(forgeInlineContent(ForgeInstance.Codeberg, Color.Black)).containsKey(icon.item)
        assertThat(forgeInlineContent(selfHosted, Color.Black).values.single()).isInstanceOf(InlineTextContent::class.java)
    }
}
