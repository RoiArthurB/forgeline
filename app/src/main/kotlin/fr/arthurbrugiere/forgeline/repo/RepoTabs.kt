package fr.arthurbrugiere.forgeline.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.SubdirectoryArrowLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.actions.RowIcon
import fr.arthurbrugiere.forgeline.actions.RunStatusIcon
import fr.arthurbrugiere.forgeline.core.markdown.ForgelineMarkdown
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.markdown.rememberReadmeState
import fr.arthurbrugiere.forgeline.core.model.DiscussionSummary
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.Badge
import fr.arthurbrugiere.forgeline.ui.loadable
import fr.arthurbrugiere.forgeline.ui.relative

/** What an empty list says: nothing matches the words, or there is none open, or none closed. */
internal fun IssueQuery.emptyMessage(noneOpen: Int, noneClosed: Int): Int = when {
    text.isNotBlank() -> R.string.repo_no_match
    open -> noneOpen
    else -> noneClosed
}

/**
 * What heads a list of issues or pull requests: the open or the closed ones, the action that opens a new one where
 * there is one, and a field to look for words among them.
 */
@Composable
internal fun ListControls(
    query: IssueQuery,
    searchHint: String,
    onShowOpen: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onNewIssue: (() -> Unit)?,
    newLabel: String = stringResource(R.string.new_issue_title),
) {
    val colors = Soft.colors
    Column(RowModifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val options = listOf(stringResource(R.string.repo_list_open), stringResource(R.string.repo_list_closed))
        // Side by side, the switch's words shrink at large text: there, the action goes under it.
        if (onNewIssue != null && LocalDensity.current.fontScale > 1.3f) {
            SoftSwitch(options, selected = if (query.open) 0 else 1, onSelect = { onShowOpen(it == 0) })
            SoftTonalButton(newLabel, onClick = onNewIssue)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftSwitch(options, selected = if (query.open) 0 else 1, onSelect = { onShowOpen(it == 0) }, modifier = Modifier.weight(1f))
                if (onNewIssue != null) SoftTonalButton(newLabel, onClick = onNewIssue)
            }
        }
        SoftTextField(
            value = query.text,
            onValueChange = onSearch,
            placeholder = searchHint,
            leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp)) },
            trailing = {
                if (query.text.isNotEmpty()) {
                    SoftIconButton(onClick = { onSearch("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear), tint = colors.inkMuted)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A quiet word above a group of rows. */
@Composable
internal fun ListTitle(text: String) {
    Text(
        text,
        style = Soft.type.label,
        color = Soft.colors.inkMuted,
        modifier = RowModifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
    )
}

/** One discussion in its repository's list: what it asks or says, where it was filed, and whether it was answered. */
@Composable
internal fun DiscussionRow(discussion: DiscussionSummary, nowMillis: Long, onOpen: () -> Unit) {
    val colors = Soft.colors
    val answered = discussion.isAnswered == true
    Row(
        Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).softPressable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).background(if (answered) colors.fields[2] else colors.surface, SoftTokens.TileCorner), contentAlignment = Alignment.Center) {
            Icon(if (answered) Icons.Outlined.CheckCircleOutline else Icons.Outlined.Forum, contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(discussion.title, style = Soft.type.name, color = colors.ink)
            val facts = listOfNotNull(
                discussion.category,
                stringResource(R.string.repo_discussion_answered).takeIf { answered },
                discussion.author?.login,
                discussion.createdAt?.let { relative(it, nowMillis) },
                pluralStringResource(R.plurals.repo_discussion_comments, discussion.comments, discussion.comments).takeIf { discussion.comments > 0 },
            )
            Text(facts.joinToString(" · "), style = Soft.type.meta, color = colors.inkMuted)
        }
    }
}

/**
 * The end of a list that has more: reaching it asks for the next page, by itself. A page that couldn't be loaded is
 * asked for by hand instead, so a forge that is down isn't asked again and again.
 */
internal fun LazyListScope.more(paging: ListPaging, onLoadMore: () -> Unit) {
    val next = paging.next ?: return
    item(key = "more") {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            if (paging.failed) {
                SoftTonalButton(stringResource(R.string.issue_load_more), onLoadMore)
            } else {
                LaunchedEffect(next) { onLoadMore() }
                val loading = stringResource(R.string.repo_loading_more)
                CircularProgressIndicator(Modifier.semantics { contentDescription = loading }, color = Soft.colors.accent, trackColor = Soft.colors.surface)
            }
        }
    }
}

@Composable
internal fun ReadmeBody(markdown: String, context: ReadmeContext, onLinkClick: (String) -> Unit) {
    val darkTheme = Soft.colors.isDark
    val parsed = rememberReadmeState(markdown, context, darkTheme)
    if (parsed == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Soft.colors.accent, trackColor = Soft.colors.surface)
        }
    } else {
        ForgelineMarkdown(parsed, onLinkClick, Modifier.widthIn(max = SoftTokens.MaxReadingWidth).padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

internal val RowModifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)

internal fun LazyListScope.code(
    code: CodeState,
    onRetry: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenParentDirectory: () -> Unit,
    onOpenFile: (RepoFile) -> Unit,
) {
    if (code.path.isNotEmpty()) {
        item(key = "code-path") {
            val colors = Soft.colors
            Row(
                RowModifier.softPressable(onClick = onOpenParentDirectory).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowIcon(Icons.Outlined.SubdirectoryArrowLeft, colors.surface)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.repo_parent_folder), style = Soft.type.body, color = colors.ink)
                    Text(code.path, style = Soft.type.meta, color = colors.inkMuted)
                }
            }
        }
    }
    loadable(code.entries, R.string.repo_empty_folder, onRetry) { entries ->
        items(entries, key = { "file-${it.path}" }) { file ->
            val colors = Soft.colors
            Row(
                RowModifier.softPressable {
                    when (file.type) {
                        RepoFileType.DIR -> onOpenDirectory(file.path)
                        RepoFileType.FILE -> onOpenFile(file)
                        RepoFileType.SYMLINK, RepoFileType.SUBMODULE -> Unit
                    }
                }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowIcon(
                    when (file.type) {
                        RepoFileType.DIR -> Icons.Outlined.Folder
                        RepoFileType.SUBMODULE, RepoFileType.SYMLINK -> Icons.Outlined.Link
                        RepoFileType.FILE -> Icons.AutoMirrored.Outlined.InsertDriveFile
                    },
                    if (file.type == RepoFileType.DIR) colors.fields[1] else colors.surface,
                )
                Spacer(Modifier.width(14.dp))
                Text(file.name, style = Soft.type.body, color = colors.ink)
            }
        }
    }
}

/** One release in the list: what it is called, its tag and age, where it stands, and how much it ships. Its page holds the rest. */
@Composable
internal fun ReleaseRow(release: Release, nowMillis: Long, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(RowModifier.softPressable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(Icons.Outlined.Sell, if (release.isPrerelease) colors.surface else colors.fields[0])
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(release.name ?: release.tag, style = Soft.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    release.tag.takeIf { release.name != null },
                    release.publishedAt?.let { relative(it, nowMillis) },
                    release.assets.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.release_assets, it, it) },
                ).joinToString(" · "),
                style = Soft.type.meta,
                color = colors.inkMuted,
            )
            if (release.isLatest || release.isPrerelease) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (release.isLatest) Badge(stringResource(R.string.release_latest))
                    if (release.isPrerelease) Badge(stringResource(R.string.repo_prerelease))
                }
            }
        }
    }
}

@Composable
internal fun RunRow(run: WorkflowRun, nowMillis: Long, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(RowModifier.softPressable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RunStatusIcon(run)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(run.title, style = Soft.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    stringResource(R.string.repo_run_meta, run.workflowName, run.branch ?: run.event, run.runNumber),
                    relative(run.createdAt, nowMillis),
                ).joinToString(" · "),
                style = Soft.type.meta,
                color = colors.inkMuted,
            )
        }
    }
}
