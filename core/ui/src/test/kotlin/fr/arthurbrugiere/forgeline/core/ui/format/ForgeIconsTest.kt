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
        assertThat(selfHosted.iconRes).isEqualTo(R.drawable.ic_forge_forgejo)
    }

    @Test
    fun only_a_self_hosted_server_writes_its_host_next_to_the_logo() {
        assertThat(ForgeInstance.GitHub.hostLabel).isNull()
        assertThat(ForgeInstance.Codeberg.hostLabel).isNull()
        assertThat(selfHosted.hostLabel).isEqualTo("git.example.org")
    }

    @Test
    fun in_a_line_of_text_the_logo_is_an_icon_named_for_screen_readers() {
        val text = buildAnnotatedString {
            appendForge(ForgeInstance.Codeberg)
            append(" · 2h")
        }

        // The name is only the logo's alternate text: an inline icon, not written out.
        val icon = text.getStringAnnotations(tag = "androidx.compose.foundation.text.inlineContent", start = 0, end = text.length).single()
        assertThat(text.text.substring(icon.start, icon.end)).isEqualTo("Codeberg")
        assertThat(forgeInlineContent(ForgeInstance.Codeberg, Color.Black)).containsKey(icon.item)
    }

    @Test
    fun a_self_hosted_server_keeps_its_host_after_the_logo() {
        val text = buildAnnotatedString { appendForge(selfHosted) }

        assertThat(text.text).endsWith("git.example.org")
        assertThat(forgeInlineContent(selfHosted, Color.Black).values.single()).isInstanceOf(InlineTextContent::class.java)
    }
}
