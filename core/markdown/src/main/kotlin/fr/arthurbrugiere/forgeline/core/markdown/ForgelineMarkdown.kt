package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown

/** Parses Markdown into a renderable state. Call off the main thread for large documents. */
fun parseForgeMarkdown(markdown: String): State = parseMarkdown(markdown)

/**
 * Renders parsed Markdown with the app theme, Coil images and highlighted code. Link taps go to
 * [onLinkClick] so the app decides what opens in-app and what goes to a Custom Tab.
 */
@Composable
fun ForgelineMarkdown(
    state: State,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = remember(onLinkClick) {
        object : UriHandler {
            override fun openUri(uri: String) = onLinkClick(uri)
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Markdown(
            state = state,
            modifier = modifier,
            imageTransformer = Coil3ImageTransformerImpl,
            components = markdownComponents(
                codeBlock = highlightedCodeBlock,
                codeFence = highlightedCodeFence,
            ),
        )
    }
}
