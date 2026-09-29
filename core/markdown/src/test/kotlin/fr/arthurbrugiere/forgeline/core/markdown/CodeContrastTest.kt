package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDark
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLight
import fr.arthurbrugiere.forgeline.core.ui.soft.amoled
import fr.arthurbrugiere.forgeline.core.ui.soft.contrastRatio
import org.junit.Test

/** Highlighted code is text: every token color must read at 4.5:1 on the ground it's shown on. */
class CodeContrastTest {
    private val sample = """
        package demo
        // Entry point
        /** Docs */
        @Suppress("unused")
        fun main(args: Array<String>) {
            val answer = 42 + 0x1F
            println("Answer: ${'$'}answer")
            if (args.isEmpty()) return else throw IllegalStateException()
        }
    """.trimIndent()

    private fun tokenColors(dark: Boolean): Set<Color> {
        val highlighted = CodeHighlighter.highlight(sample, "Main.kt", dark)
        return highlighted.spanStyles.mapNotNull { it.item.color.takeIf { color -> color != Color.Unspecified } }.toSet()
    }

    @Test
    fun token_colors_are_readable_on_every_ground() {
        for ((name, ground, dark) in listOf(Triple("light", SoftLight.ground, false), Triple("dark", SoftDark.ground, true), Triple("amoled", SoftDark.amoled().ground, true))) {
            val colors = tokenColors(dark)
            assertWithMessage("$name has highlighted tokens").that(colors).isNotEmpty()
            colors.forEach { color ->
                val ratio = contrastRatio(color, ground)
                assertWithMessage("$name token ${Integer.toHexString(color.hashCode())}: ${"%.2f".format(ratio)}:1").that(ratio).isAtLeast(4.5f)
            }
        }
    }
}
