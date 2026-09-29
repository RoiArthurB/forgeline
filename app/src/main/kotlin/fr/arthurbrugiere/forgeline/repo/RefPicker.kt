package fr.arthurbrugiere.forgeline.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.message
import androidx.compose.foundation.layout.PaddingValues

/**
 * The branch or tag the README and Code tabs show, as a pill; tapping it opens [RefSheet].
 * Tags get a tag glyph once the refs are known, branches (and anything unknown) a branch glyph.
 */
@Composable
fun RefPill(ref: String, refs: Loadable<GitRefs>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    val isTag = (refs as? Loadable.Loaded)?.value?.let { ref in it.tags && ref !in it.branches } == true
    val description = stringResource(R.string.repo_ref_switch, ref)
    Row(
        modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .clip(SoftTokens.Pill)
                .background(colors.surface)
                .softPressable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .heightIn(min = 48.dp)
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isTag) Icons.Outlined.Sell else Icons.AutoMirrored.Outlined.CallSplit,
                contentDescription = null,
                tint = colors.inkMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                ref,
                style = Soft.type.control,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The description already names the ref.
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {},
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Picks a branch or tag: a Branches/Tags switch, a filter, and the names. The default branch comes first among the
 * branches with a "Default" tag; the one browsed now is checked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefSheet(
    current: String,
    defaultBranch: String,
    refs: Loadable<GitRefs>,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Soft.colors
    val loaded = (refs as? Loadable.Loaded)?.value
    // Start on the kind being browsed, so a tag's sheet opens on the tags.
    var kind by rememberSaveable { mutableIntStateOf(if (loaded != null && current in loaded.tags && current !in loaded.branches) 1 else 0) }
    var filter by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftSwitch(
                options = listOf(stringResource(R.string.repo_ref_branches), stringResource(R.string.repo_ref_tags)),
                selected = kind,
                onSelect = { kind = it },
            )
            SoftTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = stringResource(R.string.repo_ref_filter),
                leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp)) },
            )
        }
        when (refs) {
            Loadable.Idle, Loadable.Loading -> SoftLoadingRows(stringResource(R.string.repo_ref_loading), rows = 4, leadingDot = false)
            is Loadable.Failed -> SoftNotice(
                stringResource(R.string.repo_ref_failed),
                stringResource(refs.error.message),
                action = stringResource(R.string.retry),
                onAction = onRetry,
            )
            is Loadable.Loaded -> {
                val names = remember(refs.value, kind, filter, defaultBranch) {
                    val all = if (kind == 0) listOf(defaultBranch) + (refs.value.branches - defaultBranch) else refs.value.tags
                    all.filter { it.contains(filter.trim(), ignoreCase = true) }
                }
                if (names.isEmpty()) {
                    Text(
                        stringResource(if (kind == 0) R.string.repo_ref_no_branches else R.string.repo_ref_no_tags),
                        style = Soft.type.body,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                        items(names, key = { it }) { name ->
                            RefRow(
                                name = name,
                                isDefault = kind == 0 && name == defaultBranch,
                                isCurrent = name == current,
                                onClick = { onSelect(name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefRow(name: String, isDefault: Boolean, isCurrent: Boolean, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier
            .fillMaxWidth()
            .softPressable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = Soft.type.body,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (isDefault) {
            Spacer(Modifier.width(8.dp))
            SoftTag(stringResource(R.string.repo_ref_default))
        }
    }
}
