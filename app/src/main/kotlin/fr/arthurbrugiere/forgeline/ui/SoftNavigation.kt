package fr.arthurbrugiere.forgeline.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.animationsEnabled
import fr.arthurbrugiere.forgeline.navigation.TopLevelDestination

/**
 * Space a screen leaves at its bottom so its last row can scroll clear of the floating bar (and the system
 * navigation bar). Lists add it to their bottom content padding; snackbars sit above it.
 */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

private val BarHeight = 64.dp
private val BarMargin = 12.dp

/**
 * The app shell's navigation: a floating pill bar on phones, a soft rail on wider windows. The selected tab is an
 * ember pill carrying its label; the others show their icon, named for screen readers.
 */
@Composable
fun SoftNavigation(
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = Soft.colors
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.ground)) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                SoftRail(selected, onSelect)
                Box(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalBottomBarSpace provides systemBottom, content = content)
                }
            }
        } else {
            CompositionLocalProvider(LocalBottomBarSpace provides BarHeight + BarMargin * 2 + systemBottom, content = content)
            SoftBar(selected, onSelect, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun SoftBar(selected: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = BarMargin)
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .shadow(8.dp, SoftTokens.Pill, ambientColor = colors.ink.copy(alpha = 0.18f), spotColor = colors.ink.copy(alpha = 0.18f))
            .clip(SoftTokens.Pill)
            .background(colors.raised)
            .heightIn(min = BarHeight)
            .padding(6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            BarItem(destination, destination == selected, onClick = { onSelect(destination) })
        }
    }
}

@Composable
private fun BarItem(destination: TopLevelDestination, selected: Boolean, onClick: () -> Unit) {
    val colors = Soft.colors
    val label = stringResource(destination.label)
    val animations = animationsEnabled()
    Row(
        Modifier
            .clip(SoftTokens.Pill)
            .background(if (selected) colors.thumb else colors.raised)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label }
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) destination.selectedIcon else destination.icon,
                contentDescription = null,
                tint = if (selected) colors.onThumb else colors.inkMuted,
            )
            AnimatedVisibility(
                visible = selected,
                enter = if (animations) fadeIn() + expandHorizontally() else fadeIn(snapTween()),
                exit = if (animations) fadeOut() + shrinkHorizontally() else fadeOut(snapTween()),
            ) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = Soft.type.control, color = colors.onThumb, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SoftRail(selected: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    val colors = Soft.colors
    Column(
        Modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .padding(12.dp)
            .shadow(12.dp, SoftTokens.ThumbCorner, ambientColor = colors.ink.copy(alpha = 0.25f), spotColor = colors.ink.copy(alpha = 0.25f))
            .clip(SoftTokens.ThumbCorner)
            .background(colors.raised)
            .width(88.dp)
            .padding(vertical = 12.dp, horizontal = 6.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            val label = stringResource(destination.label)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(SoftTokens.ThumbCorner)
                    .background(if (isSelected) colors.thumb else colors.raised)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(destination) })
                    .semantics { contentDescription = label }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (isSelected) destination.selectedIcon else destination.icon,
                        contentDescription = null,
                        tint = if (isSelected) colors.onThumb else colors.inkMuted,
                    )
                    Text(
                        label,
                        style = Soft.type.label.copy(fontSize = Soft.type.meta.fontSize),
                        color = if (isSelected) colors.onThumb else colors.inkMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun <T> snapTween() = androidx.compose.animation.core.tween<T>(0)

/** Bottom padding for a screen's list: breathing room plus the space the navigation takes. */
@Composable
fun listBottomPadding(extra: Dp = 24.dp): Dp = LocalBottomBarSpace.current + extra
