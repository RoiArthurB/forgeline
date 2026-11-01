package fr.arthurbrugiere.forgeline.core.markdown

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDark
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLight
import fr.arthurbrugiere.forgeline.core.ui.soft.amoled
import org.junit.Test

class CodeThemeTest {
    @Test
    fun code_follows_the_app_unless_a_theme_is_chosen_for_it() {
        assertThat(codeColors(SoftLight, dark = null)).isEqualTo(SoftLight)
        assertThat(codeColors(SoftDark.amoled(), dark = null)).isEqualTo(SoftDark.amoled())
    }

    @Test
    fun a_chosen_theme_brings_its_own_ground_under_the_code() {
        assertThat(codeColors(SoftLight, dark = true)).isEqualTo(SoftDark)
        assertThat(codeColors(SoftDark, dark = false)).isEqualTo(SoftLight)
    }

    @Test
    fun choosing_the_app_s_own_theme_keeps_its_variant() {
        // Dark code in a pure-black app stays on pure black, rather than the plain dark ground.
        assertThat(codeColors(SoftDark.amoled(), dark = true)).isEqualTo(SoftDark.amoled())
    }
}
