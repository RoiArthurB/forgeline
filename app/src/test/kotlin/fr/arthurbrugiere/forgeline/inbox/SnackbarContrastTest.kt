package fr.arthurbrugiere.forgeline.inbox

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDark
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLight
import fr.arthurbrugiere.forgeline.core.ui.soft.amoled
import fr.arthurbrugiere.forgeline.core.ui.soft.contrastRatio
import fr.arthurbrugiere.forgeline.core.ui.theme.toMaterial
import org.junit.Test

class SnackbarContrastTest {
    @Test
    fun undo_reads_on_the_ink_snackbar_in_every_theme() {
        listOf("light" to SoftLight, "dark" to SoftDark, "amoled" to SoftDark.amoled()).forEach { (name, colors) ->
            val ratio = contrastRatio(snackbarAction(colors), colors.ink)
            assertWithMessage("Undo on the $name snackbar: %s:1".format("%.2f".format(ratio))).that(ratio).isAtLeast(4.5f)
        }
    }

    @Test
    fun every_snackbar_action_reads_by_default() {
        // Snackbars that don't pick an action colour take Material's inversePrimary: it must hold too.
        listOf("light" to SoftLight, "dark" to SoftDark, "amoled" to SoftDark.amoled()).forEach { (name, colors) ->
            val scheme = colors.toMaterial()
            val ratio = contrastRatio(scheme.inversePrimary, scheme.inverseSurface)
            assertWithMessage("Default action on the $name snackbar: %s:1".format("%.2f".format(ratio))).that(ratio).isAtLeast(4.5f)
        }
    }
}
