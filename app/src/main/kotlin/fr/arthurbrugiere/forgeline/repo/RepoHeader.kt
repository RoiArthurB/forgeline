package fr.arthurbrugiere.forgeline.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoLanguage
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeMark
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.format.languageColor
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.LocalOpenTopic

@Composable
internal fun RepoHeader(
    details: RepoDetails,
    starred: Boolean?,
    signedIn: Boolean,
    onToggleStar: () -> Unit,
    onLinkClick: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    watching: Boolean? = null,
    onToggleWatch: () -> Unit = {},
    isForking: Boolean = false,
    onFork: () -> Unit = {},
    onOpenWiki: () -> Unit = {},
    languages: List<RepoLanguage> = emptyList(),
) {
    val colors = Soft.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clip(SoftTokens.Pill).clickable { onOpenUser(details.id.owner) }.padding(end = 8.dp),
            ) {
                Avatar(details.ownerAvatarUrl, details.id.owner, size = 28.dp, placeholderColor = colors.surface, placeholderContentColor = colors.inkMuted)
                Text(details.id.owner, style = Soft.type.secondary, color = colors.inkMuted)
            }
            // Where the repository lives: always said here, whatever else is signed in.
            ForgeMark(details.id.forge, style = Soft.type.secondary, color = colors.inkMuted, size = 16.dp)
        }
        Text(details.id.name, style = Soft.type.detailTitle, color = colors.ink, modifier = Modifier.semantics { heading() })
        details.description?.let {
            Text(it, style = Soft.type.lead, color = colors.ink, modifier = Modifier.widthIn(max = SoftTokens.MaxMeasure))
        }
        details.homepage?.let { url ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clip(SoftTokens.Pill).clickable { onLinkClick(url) },
            ) {
                Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.accent)
                Text(url.removePrefix("https://").removePrefix("http://").trimEnd('/'), style = Soft.type.secondary, color = colors.accent)
            }
        }
        if (details.topics.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Each opens its topic: what else is tagged so, on every forge.
                val openTopic = LocalOpenTopic.current
                details.topics.forEach { topic ->
                    val tap = openTopic?.let { open ->
                        Modifier.clip(SoftTokens.TagCorner).clickable(role = Role.Button, onClickLabel = stringResource(R.string.topic_open, topic)) { open(topic) }
                    }
                    SoftTag(topic, modifier = tap ?: Modifier, background = colors.surface)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Stat(stringResource(R.string.trending_stars, compactCount(details.stars)))
            Stat(stringResource(R.string.trending_forks, compactCount(details.forks)))
            Stat(stringResource(R.string.repo_watchers, compactCount(details.watchers)))
            // The main language alone until every language is known: the bar below says them all.
            if (languages.isEmpty()) {
                details.language?.let { language ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        languageColor(language)?.let { dot ->
                            Box(Modifier.size(9.dp).background(dot, CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Stat(language)
                    }
                }
            }
            details.license?.let { Stat(it) }
        }
        if (languages.isNotEmpty()) LanguageBar(languages)
        if (details.isArchived || details.isFork) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (details.isArchived) SoftTag(stringResource(R.string.repo_archived), background = colors.surface)
                if (details.isFork) SoftTag(stringResource(R.string.repo_fork), background = colors.surface)
            }
        }
        val isStarred = starred == true && signedIn
        // Side by side while they fit; at large text or on a narrow screen they go one under another.
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderAction(
                icon = if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                label = stringResource(if (isStarred) R.string.repo_starred else R.string.repo_star),
                // The one thing asked of a passer-by stands out until it is done.
                prominent = !isStarred,
                on = isStarred,
                onToggle = onToggleStar,
            )
            // Watching and forking are an account's: nothing is offered before the forge has said where it stands.
            if (signedIn && watching != null) {
                HeaderAction(
                    icon = if (watching) Icons.Filled.Visibility else Icons.Outlined.Visibility,
                    label = stringResource(if (watching) R.string.repo_watching else R.string.repo_watch),
                    on = watching,
                    onToggle = onToggleWatch,
                )
            }
            // Where a repository has a wiki, its first page opens.
            if (details.hasWiki) {
                HeaderAction(icon = Icons.AutoMirrored.Outlined.MenuBook, label = stringResource(R.string.repo_wiki), onClick = onOpenWiki)
            }
            if (signedIn) {
                HeaderAction(
                    icon = Icons.AutoMirrored.Outlined.CallSplit,
                    label = stringResource(if (isForking) R.string.repo_forking else R.string.repo_fork_action),
                    enabled = !isForking,
                    onClick = onFork,
                )
            }
        }
    }
}

/**
 * One of the header's actions, as a small button. With [onToggle] it is a switch, so screen readers hear "on" or "off" as well
 * as the label; with [onClick] a button.
 */

/**
 * What the code is written in, as GitHub draws it: one bar shared between the languages in their colours, then the
 * main ones named with their share. Past [LEGEND], the rest is said as one "Other" share.
 */
@Composable
private fun LanguageBar(languages: List<RepoLanguage>) {
    val colors = Soft.colors
    val shown = languages.take(LEGEND)
    val other = languages.drop(LEGEND).sumOf { it.share }
    val unknown = colors.inkMuted.copy(alpha = 0.45f)
    val said = (shown.map { "${it.name} ${percent(it.share)}" } + listOfNotNull(if (other > 0) "${stringResource(R.string.repo_languages_other)} ${percent(other)}" else null)).joinToString(", ")
    Column(Modifier.widthIn(max = SoftTokens.MaxMeasure).fillMaxWidth().clearAndSetSemantics { contentDescription = said }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // Slivers under half a percent would be hairlines between gaps: they join "Other" in the bar too.
            val bars = languages.filter { it.share >= 0.005 }
            bars.forEach { language ->
                Box(Modifier.weight(language.share.toFloat()).fillMaxHeight().background(languageColor(language.name) ?: unknown))
            }
            val rest = 1.0 - bars.sumOf { it.share }
            if (rest > 0.0005) Box(Modifier.weight(rest.toFloat()).fillMaxHeight().background(unknown))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEach { LanguageShare(it.name, it.share, languageColor(it.name) ?: unknown) }
            if (other > 0) LanguageShare(stringResource(R.string.repo_languages_other), other, unknown)
        }
    }
}

@Composable
private fun LanguageShare(name: String, share: Double, dot: Color) {
    val colors = Soft.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(with(LocalDensity.current) { 9.sp.toDp() }).background(dot, CircleShape))
        Text(name, style = Soft.type.meta.copy(fontWeight = FontWeight.SemiBold), color = colors.ink)
        Text(percent(share), style = Soft.type.meta, color = colors.inkMuted)
    }
}

/** A share as people read it: one decimal, "<0.1%" for less. */
private fun percent(share: Double): String =
    if (share < 0.001) "<0.1%" else String.format(java.util.Locale.getDefault(), "%.1f%%", share * 100)

/** Languages named under the bar; the rest share one "Other". */
private const val LEGEND = 4

@Composable
private fun HeaderAction(
    icon: ImageVector,
    label: String,
    prominent: Boolean = false,
    on: Boolean = false,
    enabled: Boolean = true,
    onToggle: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val colors = Soft.colors
    Box(
        Modifier
            .clip(SoftTokens.Pill)
            .background(if (prominent) colors.thumb else colors.surface)
            .then(
                if (onToggle != null) Modifier.toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
                else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = when {
                    prominent -> colors.onThumb
                    on -> colors.accent
                    else -> colors.ink.copy(alpha = if (enabled) 1f else 0.5f)
                },
                modifier = Modifier.size(18.dp),
            )
            Text(label, style = Soft.type.control, color = if (prominent) colors.onThumb else colors.ink.copy(alpha = if (enabled) 1f else 0.5f))
        }
    }
}

@Composable
private fun Stat(text: String) {
    Text(text, style = Soft.type.meta, color = Soft.colors.inkMuted)
}
