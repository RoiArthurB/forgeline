package fr.arthurbrugiere.forgeline.feed

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.arthurbrugiere.forgeline.core.model.ConversationPreview
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.ReleaseJump
import fr.arthurbrugiere.forgeline.core.model.ReleasePreview
import fr.arthurbrugiere.forgeline.core.model.releaseJump
import fr.arthurbrugiere.forgeline.ui.emoji
import fr.arthurbrugiere.forgeline.ui.label
import fr.arthurbrugiere.forgeline.ui.relativeInSentence
import fr.arthurbrugiere.forgeline.core.ui.format.iconRes
import fr.arthurbrugiere.forgeline.core.ui.soft.rowHairline
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTile
import androidx.compose.ui.res.painterResource
import fr.arthurbrugiere.forgeline.ui.LocalUserSettings
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import fr.arthurbrugiere.forgeline.core.ui.soft.animationsEnabled
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import fr.arthurbrugiere.forgeline.ui.LocalOpenRelease
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.relative
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.ui.text.withStyle
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.format.languageColor

/**
 * One event: who did what and when, then the thing it's about, drawn for its kind. A release leads with its
 * project's logo, its version and how big a step it is, the project's picture, what its notes say first and how
 * people reacted; an issue or pull request with its title, then its state, size, labels and comments.
 */
@Composable
internal fun FeedRow(
    item: FeedItem,
    repoPreview: RepoPreview?,
    release: ReleasePreview?,
    conversation: ConversationPreview?,
    nowMillis: Long,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (ForgeInstance, String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val actor = item.actors.first()
    val openRelease = LocalOpenRelease.current
    val openDiscussion = fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion.current
    // Which forge (its logo, on the corner of the tile), once more than one is signed in.
    val forge = item.repo.forge.takeIf { LocalShowForge.current }
    val badge = forge?.let { painterResource(it.iconRes) }
    Row(
        modifier
            .fillMaxWidth()
            .softPressable { item.open(onOpenRepo, onOpenIssue, onOpenUser, onOpenUrl, openRelease, openDiscussion) }
            .rowHairline(start = 60.dp)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (item.action is FeedAction.Released) {
            // A release is the project's: its logo, as the project drew it (round ones stay round, with nothing behind
            // them). The tag on marigold until the logo is known.
            val logo = release?.logoUrl
            SoftTile(background = if (logo == null) colors.accentSoft else Color.Transparent, badge = badge, badgeDescription = forge?.displayName) {
                if (logo == null) {
                    Icon(Icons.Outlined.Sell, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                } else {
                    Avatar(logo, item.repo.owner, size = SoftTokens.Tile, shape = SoftTokens.TileCorner, placeholderColor = colors.accentSoft, placeholderContentColor = colors.accent)
                }
            }
        } else {
            // People are round: the actor's face, as tall as a row's tile.
            SoftTile(shape = CircleShape, badge = badge, badgeDescription = forge?.displayName) {
                Avatar(
                    actor.avatarUrl,
                    actor.login,
                    size = SoftTokens.Tile,
                    placeholderColor = colors.surface,
                    placeholderContentColor = colors.inkMuted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onOpenUser(item.repo.forge, actor.login) }
                        .semantics { contentDescription = actor.login },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // A name is never broken across lines, and GitLab's can be long (group/subgroup/project): one that doesn't
            // leave room pushed the end of the line out of sight, which is the forge and the time. The repository's
            // name gives way first, step by step, then the row takes one more line.
            var squeeze by remember(item.key, forge) { mutableIntStateOf(0) }
            val repoLabel = feedRepoLabel(item.repo.fullName, squeeze)
            val lines = (if (LocalDensity.current.fontScale > 1.3f) 4 else 2) + if (squeeze > LAST_SQUEEZE) 1 else 0
            Text(
                buildAnnotatedString {
                    append(item.headline(repoLabel).emphasizing(item.names(repoLabel), colors.ink))
                    // Each dot sticks to what precedes it, so a line never starts with "·"; the time is kept whole.
                    val time = relative(item.createdAt, nowMillis, abbreviated = true).replace(' ', ' ')
                    withStyle(SpanStyle(color = colors.inkMuted)) { append(" · $time") }
                },
                style = Soft.type.secondary,
                color = colors.inkMuted,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow) squeeze = nextFeedSqueeze(item.repo.fullName, squeeze) },
                modifier = Modifier.padding(top = 2.dp),
            )
            FeedObject(item, repoPreview, release, conversation, nowMillis)
        }
    }
}

/** The thing an event is about, drawn for its kind. */
@Composable
private fun FeedObject(item: FeedItem, repoPreview: RepoPreview?, release: ReleasePreview?, conversation: ConversationPreview?, nowMillis: Long) {
    val colors = Soft.colors
    val (closed, merged, open) = colors.fieldInks
    when (val a = item.action) {
        FeedAction.Starred, FeedAction.MadePublic, is FeedAction.Forked -> RepoObject(item.repo, repoPreview, null, nowMillis)
        is FeedAction.CreatedRepo -> RepoObject(item.repo, repoPreview, a.description, nowMillis)
        is FeedAction.Released -> ReleaseObject(a, release)
        is FeedAction.Announced -> Column {
            ObjectTitle(a.title)
            MetaRow { StateMark(stringResource(R.string.feed_state_announcement), Icons.Outlined.Campaign, merged, a.number) }
        }
        is FeedAction.Issue -> Column {
            ObjectTitle(conversation?.title ?: a.title)
            MetaRow {
                when (a.action) {
                    IssueAction.OPENED -> StateMark(stringResource(R.string.feed_state_open), Icons.Outlined.Adjust, open, a.number)
                    IssueAction.REOPENED -> StateMark(stringResource(R.string.feed_state_reopened), Icons.Outlined.Replay, open, a.number)
                    IssueAction.CLOSED -> StateMark(stringResource(R.string.feed_state_closed), Icons.Outlined.CheckCircleOutline, closed, a.number)
                }
                ConversationMarks(conversation)
            }
        }
        is FeedAction.PullRequest -> Column {
            LateTitle(a.title ?: conversation?.title)
            MetaRow {
                when (a.action) {
                    PullRequestAction.OPENED -> StateMark(stringResource(R.string.feed_state_open), Icons.AutoMirrored.Outlined.CallMerge, open, a.number)
                    PullRequestAction.REOPENED -> StateMark(stringResource(R.string.feed_state_reopened), Icons.AutoMirrored.Outlined.CallMerge, open, a.number)
                    PullRequestAction.MERGED -> StateMark(stringResource(R.string.feed_state_merged), Icons.AutoMirrored.Outlined.CallMerge, merged, a.number)
                    PullRequestAction.CLOSED -> StateMark(stringResource(R.string.feed_state_closed), Icons.Outlined.Close, closed, a.number)
                }
                ConversationMarks(conversation)
            }
        }
        is FeedAction.Reviewed -> Column {
            LateTitle(conversation?.title)
            MetaRow {
                when (a.state) {
                    ReviewState.APPROVED -> StateMark(stringResource(R.string.feed_state_approved), Icons.Outlined.Check, open, a.number)
                    ReviewState.CHANGES_REQUESTED -> StateMark(stringResource(R.string.feed_state_changes), Icons.Outlined.RateReview, closed, a.number)
                    else -> StateMark(stringResource(R.string.feed_state_reviewed), Icons.Outlined.RateReview, colors.inkMuted, a.number)
                }
            }
        }
        is FeedAction.Commented -> Column {
            LateTitle(a.title ?: conversation?.title)
            MetaRow { StateMark(stringResource(R.string.feed_state_comment), Icons.Outlined.ChatBubbleOutline, colors.inkMuted, a.number) }
        }
        is FeedAction.Pushed -> MetaRow { RefMark(a.branch, Icons.Outlined.Commit) }
        is FeedAction.Branch -> MetaRow { RefMark(a.name, if (a.isTag) Icons.Outlined.Sell else Icons.Outlined.AccountTree) }
        // The line already says who joined where.
        is FeedAction.AddedMember -> Unit
    }
}

/**
 * A release: its version large with the size of the step, its name when that says more than the tag, the project's
 * picture, what the notes say first, and how people reacted. The row opens the full notes.
 */
@Composable
private fun ReleaseObject(action: FeedAction.Released, release: ReleasePreview?) {
    val colors = Soft.colors
    val pictures = LocalUserSettings.current.feedPictures
    val startedEmpty = remember(action.tag) { release == null }
    Column(Modifier.widthIn(max = SoftTokens.MaxMeasure)) {
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Text(action.tag, style = Soft.type.version, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val jump = release?.let { releaseJump(action.tag, it.previousTag) }
            val step = listOfNotNull(jump?.let { stringResource(it.label) }, if (action.prerelease) stringResource(R.string.repo_prerelease) else null)
            if (step.isNotEmpty()) {
                // A patch is routine; a new minor or major version, or a pre-release, is news.
                val news = action.prerelease || jump == ReleaseJump.MAJOR || jump == ReleaseJump.MINOR
                Text(step.joinToString(" · "), style = Soft.type.label, color = if (news) colors.accent else colors.inkMuted, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        action.name?.takeIf { it != action.tag && it.removePrefix("v") != action.tag.removePrefix("v") }?.let {
            Text(it, style = Soft.type.secondary, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
        val image = release?.imageUrl?.takeIf { pictures }
        if (image != null) ReleasePicture(image)
        when {
            release == null -> PendingTitle()
            else -> FadeInIfLate(late = startedEmpty) {
                Column {
                    release.summary?.let {
                        Text(
                            it,
                            style = Soft.type.secondary,
                            color = colors.ink,
                            maxLines = if (image != null) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = if (image != null) 8.dp else 4.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Reactions(release.reactions, Modifier.weight(1f))
                        Text(stringResource(R.string.feed_release_notes), style = Soft.type.label, color = colors.accent)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.accent, modifier = Modifier.size(textSized(16)))
                    }
                }
            }
        }
    }
}

/** The project's picture, at the width of the row, two to one: its social image, or the first picture in its notes. */
@Composable
private fun ReleasePicture(url: String) {
    val colors = Soft.colors
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .testTag(RELEASE_PICTURE_TAG)
            .padding(top = 9.dp)
            .fillMaxWidth()
            .aspectRatio(2f)
            .clip(SoftTokens.TileCorner)
            .background(colors.surface)
            .border(Dp.Hairline, colors.hairline, SoftTokens.TileCorner),
    )
}

internal const val RELEASE_PICTURE_TAG = "release-picture"

/** How people reacted, most given first: the emoji and its count, four kinds at most. */
@Composable
private fun Reactions(reactions: Map<Reaction, Int>, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    val said = reactions.entries.sortedByDescending { it.value }.take(4)
    val description = said.map { (reaction, count) -> stringResource(R.string.issue_reaction_count, stringResource(reaction.label), count) }.joinToString("; ")
    Row(modifier.clearAndSetSemantics { if (description.isNotEmpty()) contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        said.forEach { (reaction, count) ->
            Text("${reaction.emoji} ${compactCount(count)}", style = Soft.type.meta, color = colors.inkMuted)
        }
    }
}

/** [dp] at the default text size, growing with the reader's text size so a mark keeps its place beside its words. */
@Composable
private fun textSized(dp: Int): Dp = with(LocalDensity.current) { dp.sp.toDp() }

/** A line of small facts under a title: a state, a size, labels, a count. They wrap rather than squeeze. */
@Composable
private fun MetaRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.padding(top = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A state in its meaning's ink, with its glyph and number: "Merged #3412". */
@Composable
private fun StateMark(label: String, icon: ImageVector, ink: Color, number: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(textSized(15)))
        Text("$label #$number", style = Soft.type.label, color = ink)
    }
}

/** What an issue or pull request adds once known: its size, files changed, labels and comments. */
@Composable
private fun ConversationMarks(conversation: ConversationPreview?) {
    val colors = Soft.colors
    if (conversation == null) return
    val added = conversation.additions
    val removed = conversation.deletions
    if (added != null && removed != null) SizeMark(added, removed)
    conversation.changedFiles?.let { files ->
        Text(pluralStringResource(R.plurals.feed_files, files, files), style = Soft.type.meta, color = colors.inkMuted)
    }
    conversation.labels.take(3).forEach { label -> LabelMark(label) }
    conversation.comments?.takeIf { it > 0 }?.let { comments ->
        val said = pluralStringResource(R.plurals.feed_comments, comments, comments)
        Row(Modifier.clearAndSetSemantics { contentDescription = said }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(textSized(14)))
            Text(compactCount(comments), style = Soft.type.meta, color = colors.inkMuted)
        }
    }
}

/** Lines added and removed, then five squares shared between them the way GitHub draws a change's size. */
@Composable
private fun SizeMark(added: Int, removed: Int) {
    val colors = Soft.colors
    val (red, _, green) = colors.fieldInks
    val said = stringResource(R.string.feed_size, added, removed)
    val greens = if (added + removed == 0) 0 else ((5f * added / (added + removed)) + 0.5f).toInt().coerceIn(if (added > 0) 1 else 0, if (removed > 0) 4 else 5)
    Row(Modifier.clearAndSetSemantics { contentDescription = said }, verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = green)) { append("+${compactCount(added)}") }
                append(" ")
                withStyle(SpanStyle(color = red)) { append("−${compactCount(removed)}") }
            },
            style = Soft.type.meta,
        )
        Spacer(Modifier.width(5.dp))
        val square = textSized(7)
        repeat(5) { i ->
            Box(Modifier.padding(start = if (i == 0) 0.dp else 2.dp).size(square).background(if (i < greens) green else red, RoundedCornerShape(1.5.dp)))
        }
    }
}

/** A forge label: its color as a dot, then its name. */
@Composable
private fun LabelMark(label: Label) {
    val dot = label.color?.let { runCatching { Color(0xFF000000 or it.removePrefix("#").toLong(16)) }.getOrNull() } ?: Soft.colors.inkMuted
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(textSized(8)).background(dot, CircleShape))
        Text(label.name, style = Soft.type.tag, color = Soft.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
    }
}

/** A branch or tag by name, in code type. */
@Composable
private fun RefMark(name: String, icon: ImageVector) {
    val colors = Soft.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(textSized(15)))
        Text(name, style = Soft.type.codeDense, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A repository at a glance: its name, what it says it is, its language, stars and when it last moved. */
@Composable
private fun RepoObject(repo: RepoId, preview: RepoPreview?, description: String?, nowMillis: Long) {
    val colors = Soft.colors
    val startedEmpty = remember(repo) { preview == null }
    Column(Modifier.widthIn(max = SoftTokens.MaxMeasure).padding(top = 4.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.inkMuted, fontWeight = FontWeight.Normal)) { append("${repo.owner}/⁠") }
                append(repo.name)
            },
            style = Soft.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        description?.let { RepoDescription(it) }
        if (preview != null) {
            FadeInIfLate(late = startedEmpty) {
                Column {
                    if (description == null) preview.description?.let { RepoDescription(it) }
                    RepoMeta(preview, nowMillis)
                }
            }
        }
    }
}

@Composable
private fun RepoDescription(text: String) {
    Text(text, style = Soft.type.secondary, color = Soft.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
}

/** A repository's language (with its linguist color), stars, and when something was last pushed to it. */
@Composable
private fun RepoMeta(preview: RepoPreview, nowMillis: Long) {
    val colors = Soft.colors
    MetaRow {
        preview.language?.let { language ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                languageColor(language)?.let { dot ->
                    Box(Modifier.size(textSized(9)).background(dot, CircleShape))
                    Spacer(Modifier.width(5.dp))
                }
                Text(language, style = Soft.type.meta, color = colors.inkMuted)
            }
        }
        Text(stringResource(R.string.trending_stars, compactCount(preview.stars)), style = Soft.type.meta, color = colors.inkMuted)
        preview.pushedAt?.let { Text(stringResource(R.string.feed_updated, relativeInSentence(it, nowMillis)), style = Soft.type.meta, color = colors.inkMuted) }
    }
}

/**
 * Fades [content] in when it arrived after its row was already on screen ([late]); content that was there from the
 * start (cached previews scrolling in) shows at once.
 */
@Composable
private fun FadeInIfLate(late: Boolean, content: @Composable () -> Unit) {
    val animate = late && animationsEnabled()
    val alpha = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) alpha.animateTo(1f, tween(PREVIEW_MILLIS)) }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

private const val PREVIEW_MILLIS = 220

/** How a release's step reads. */
private val ReleaseJump.label: Int
    get() = when (this) {
        ReleaseJump.MAJOR -> R.string.feed_jump_major
        ReleaseJump.MINOR -> R.string.feed_jump_minor
        ReleaseJump.PATCH -> R.string.feed_jump_patch
    }

/** The title of what an event is about, set to be read. */
@Composable
private fun ObjectTitle(title: String) {
    Text(
        title,
        style = Soft.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
        color = Soft.colors.ink,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp).widthIn(max = SoftTokens.MaxMeasure),
    )
}

/**
 * A title that may still be on its way (a pull request's: carried by Forgejo's events, fetched after the Feed shows for
 * GitHub's, which give only the number): its placeholder bar until then, and a fade when it lands.
 */
@Composable
private fun LateTitle(title: String?) {
    val startedEmpty = remember { title == null }
    if (title == null) PendingTitle() else FadeInIfLate(late = startedEmpty) { ObjectTitle(title) }
}

/** Where text will land: a soft bar, so the row keeps its shape. */
@Composable
private fun PendingTitle() {
    Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.7f).height(14.dp).background(Soft.colors.surface, SoftTokens.Pill))
}
