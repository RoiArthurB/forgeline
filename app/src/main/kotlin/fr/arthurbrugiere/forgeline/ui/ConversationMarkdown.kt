package fr.arthurbrugiere.forgeline.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import fr.arthurbrugiere.forgeline.core.markdown.ForgelineMarkdown
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.markdown.ReferenceLinks
import fr.arthurbrugiere.forgeline.core.markdown.rememberReadmeState
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft

// The Markdown people write in conversations, discussions and releases: how it is read, and what its #12s point to.

/** Where the conversations a text of this repository names by number live: `#12`, and `!12` where merge requests are numbered apart. */
fun RepoId.referenceLinks(): ReferenceLinks = when (forge.type) {
    ForgeType.GITLAB -> ReferenceLinks(forge.webUrl, fullName, issuePath = "/-/issues/", mergeRequestPath = "/-/merge_requests/")
    else -> ReferenceLinks(forge.webUrl, fullName, issuePath = "/issues/")
}

/** On a comment body still being parsed; lets tests wait until every body is rendered. */
const val MARKDOWN_PENDING_TAG = "markdown-pending"

/** The count of comments in sight whose Markdown is still being read, kept by the conversation they are in. */
internal val LocalUnreadMarkdown = compositionLocalOf<MutableIntState> { mutableIntStateOf(0) }

@Composable
internal fun Markdown(body: String, context: ReadmeContext, onLinkClick: (String) -> Unit) {
    val colors = Soft.colors
    val parsed = rememberReadmeState(body, context, colors.isDark)
    if (parsed == null) {
        // Counted while it shows: a list sent somewhere waits for it before letting go.
        val unread = LocalUnreadMarkdown.current
        DisposableEffect(unread) {
            unread.intValue++
            onDispose { unread.intValue-- }
        }
        // Styled like the rendered paragraph, so nothing jumps when parsing lands.
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(MARKDOWN_PENDING_TAG),
        )
    } else {
        ForgelineMarkdown(parsed, onLinkClick)
    }
}
