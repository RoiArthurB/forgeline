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

    @Test
    fun what_a_header_holds_is_drawn_in_the_band_s_palette() {
        // Regression: a screen's colors, read before its header, put dark ink on the ink band in light theme (titles
        // and repository lines vanished). Inside a SoftHeader, colors come from Soft.colors, which is the band's there.
        val captured = Regex("""(?<![\w.])colors\.""")
        val offenders = screens.flatMap { file ->
            val path = file.relativeTo(Repo.root).invariantSeparatorsPath
            val text = file.readText()
            headerCalls(text).filter { (start, end) -> captured.containsMatchIn(text.substring(start, end)) }
                .map { (start, _) -> "$path:${text.substring(0, start).count { it == '\n' } + 1}" }
        }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun corners_stay_small() {
        // No pills and no big soft corners: controls take 6dp, tiles 8dp, a sheet at most 12dp.
        val big = Regex("""RoundedCornerShape\((1[3-9]|[2-9][0-9])\.dp\)|RoundedCornerShape\(percent""")
        assertThat(offenders(big)).isEmpty()
    }

    @Test
    fun text_comes_in_geist_s_two_weights() {
        // Geist ships Regular and SemiBold only: any other weight is faked or falls back. A job's log is monospace,
        // whose bold is real.
        val other = Regex("""FontWeight\.(Thin|ExtraLight|Light|Medium|Bold|ExtraBold|Black)\b""")
        val log = "app/src/main/kotlin/fr/arthurbrugiere/forgeline/actions/Ansi.kt"
        assertThat(offenders(other, except = setOf(log))).isEmpty()
    }

    /** Where each `SoftHeader(...)` call sits in [text], its trailing content lambda included. */
    private fun headerCalls(text: String): List<Pair<Int, Int>> {
        val calls = mutableListOf<Pair<Int, Int>>()
        var from = 0
        while (true) {
            val start = text.indexOf("SoftHeader(", from).takeIf { it >= 0 } ?: break
            if (text.substring(maxOf(0, start - 4), start).contains("fun ")) { from = start + 1; continue }
            var end = closing(text, start + "SoftHeader".length, '(', ')')
            var next = end
            while (next < text.length && text[next] == ' ') next++
            if (next < text.length && text[next] == '{') end = closing(text, next, '{', '}')
            calls += start to end
            from = end
        }
        return calls
    }

    private fun closing(text: String, open: Int, opening: Char, closingChar: Char): Int {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                opening -> depth++
                closingChar -> if (--depth == 0) return index + 1
            }
        }
        return text.length
    }
}
