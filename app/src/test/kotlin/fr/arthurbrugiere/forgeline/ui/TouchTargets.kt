package fr.arthurbrugiere.forgeline.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.google.common.truth.Truth.assertWithMessage

/**
 * Every tappable thing on screen is at least 48dp both ways (Material's minimum touch target). Measured on the touch
 * bounds, so a small visual with an enlarged touch area passes. Names the offenders by their text or description.
 */
fun ComposeContentTestRule.assertEveryTargetIsAtLeast48dp() {
    val minPx = density.run { 48f * this.density } - 0.5f
    val offenders = onNode(isRoot()).fetchSemanticsNode().let { root -> collect(root) }
        .filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
        .filter { it.touchBoundsInRoot.width < minPx || it.touchBoundsInRoot.height < minPx }
        .map { node ->
            val name = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: node.children.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
                ?: "#${node.id}"
            "$name (${(node.touchBoundsInRoot.width / density.density).toInt()}x${(node.touchBoundsInRoot.height / density.density).toInt()}dp)"
        }
    assertWithMessage("Targets under 48dp").that(offenders).isEmpty()
}

private fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
