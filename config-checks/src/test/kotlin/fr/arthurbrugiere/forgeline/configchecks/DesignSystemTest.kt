package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Rules of DESIGN.md that a screen can break without any test of its own noticing. */
class DesignSystemTest {
    private val screens = Repo.sources(Regex("""^(app|core/ui)/src/main/.*\.kt$""")).toList()

    private fun offenders(pattern: Regex, except: Set<String> = emptySet()) = screens
        .flatMap { file ->
            val path = file.relativeTo(Repo.root).invariantSeparatorsPath
            file.readLines().mapIndexedNotNull { index, line -> "$path:${index + 1}".takeIf { pattern.containsMatchIn(line) } }
        }
        .filterNot { it.substringBeforeLast(':') in except }

    @Test
    fun nothing_pressable_answers_with_material_s_ripple() {
        // The Soft Press Rule: a press is the soft surface or the soft veil. Material's icon buttons draw a ripple of
        // their own whatever the theme says, so icons are pressed through SoftIconButton and SoftIconToggleButton.
        assertThat(offenders(Regex("""\b(Filled|FilledTonal|Outlined)?Icon(Toggle)?Button\("""))).isEmpty()
    }

    @Test
    fun every_text_takes_its_size_from_the_type_scale() {
        // The Named Style Rule: a size typed into a screen is drift; a new need gets a named style in SoftType.
        // Soft.kt is where the scale is set. The reaction glyphs are pictures that happen to be emoji, sized like the
        // reactions under a comment, not text.
        val handSet = Regex("""fontSize\s*=\s*[0-9.]+f?\.sp""")
        val scale = "core/ui/src/main/kotlin/fr/arthurbrugiere/forgeline/core/ui/soft/Soft.kt"
        val reactionGlyphs = "app/src/main/kotlin/fr/arthurbrugiere/forgeline/issue/IssueScreen.kt"

        assertThat(offenders(handSet, except = setOf(scale, reactionGlyphs))).isEmpty()
    }
}
