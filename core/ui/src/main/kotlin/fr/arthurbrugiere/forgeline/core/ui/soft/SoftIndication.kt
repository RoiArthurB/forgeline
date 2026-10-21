package fr.arthurbrugiere.forgeline.core.ui.soft

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * How anything pressable answers a press in the Soft world: a soft ink veil over it while pressed or focused, and no
 * ripple. Set once as the default indication, so every clickable, selectable and toggleable row, pill and button
 * answers the same way; [softPressable] rows fill with the tile surface instead. [veil] is the track colour: a 6-7% ink (or
 * white) wash that follows whatever it lies over.
 */
data class SoftIndication(val veil: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = VeilNode(interactionSource, veil)
}

private class VeilNode(private val source: InteractionSource, private val veil: Color) : Modifier.Node(), DrawModifierNode {
    private var pressed = 0
    private var focused = 0

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                    is FocusInteraction.Focus -> focused++
                    is FocusInteraction.Unfocus -> focused = (focused - 1).coerceAtLeast(0)
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed > 0 || focused > 0) drawRect(veil)
    }
}
