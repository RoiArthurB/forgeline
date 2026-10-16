package fr.arthurbrugiere.forgeline.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.animationsEnabled
import fr.arthurbrugiere.forgeline.navigation.TopLevelDestination

/**
 * Space a screen leaves at its bottom so its last row can scroll clear of the bar (and the system navigation bar).
 * Lists add it to their bottom content padding; snackbars sit above it.
 */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

private val BarHeight = 56.dp

/**
 * The app shell's navigation: a bar along the bottom on phones, a rail along the start on wider windows, both on the
 * page with a hairline between. Every tab shows its label under its icon; the chosen one is filled and bold, with a
 * marigold bar on its outer edge. Each is also named once for screen readers.
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
                Box(Modifier.width(Dp.Hairline).fillMaxHeight().background(colors.hairline))
                Box(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalBottomBarSpace provides systemBottom, content = content)
                }
            }
        } else {
            CompositionLocalProvider(LocalBottomBarSpace provides BarHeight + systemBottom, content = content)
            SoftBar(selected, onSelect, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun SoftBar(selected: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    Column(modifier.fillMaxWidth().background(colors.ground)) {
        Box(Modifier.fillMaxWidth().height(Dp.Hairline).background(colors.hairline))
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .heightIn(min = BarHeight)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                BarItem(destination, destination == selected, onClick = { onSelect(destination) })
            }
        }
    }
}

@Composable
private fun RowScope.BarItem(destination: TopLevelDestination, selected: Boolean, onClick: () -> Unit) {
    val colors = Soft.colors
    val label = stringResource(destination.label)
    val animations = animationsEnabled()
    // Labels follow the font size up to 1.15x, then hold: four tabs share the bar, and a clipped "Inb…" names nothing. Every
    // tab is still named in full for screen readers.
    val labelSize = Soft.type.meta.fontSize * minOf(1f, 1.15f / LocalDensity.current.fontScale)
    val bar by animateColorAsState(if (selected) colors.thumb else Color.Transparent, if (animations) tween(180) else snapTween(), label = "bar")
    // Every tab carries its label: an icon alone asks the visitor to remember which glyph is Feed.
    Box(
        Modifier
            .weight(1f)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label }
            .heightIn(min = BarHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth(0.44f).height(3.dp).background(bar))
        Column(Modifier.clearAndSetSemantics {}.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val ink = if (selected) colors.ink else colors.inkMuted
            Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null, tint = ink)
            Text(
                label,
                style = Soft.type.meta.copy(fontSize = labelSize, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SoftRail(selected: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    val colors = Soft.colors
    Column(
        Modifier
            .fillMaxHeight()
            .background(colors.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .width(80.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            val label = stringResource(destination.label)
            Box(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(destination) })
                    .semantics { contentDescription = label }
                    .heightIn(min = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(32.dp).background(colors.thumb))
                Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                    val ink = if (isSelected) colors.ink else colors.inkMuted
                    Icon(if (isSelected) destination.selectedIcon else destination.icon, contentDescription = null, tint = ink)
                    Text(
                        label,
                        style = Soft.type.meta.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                        color = ink,
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

/**
 * Keeps a screen clear of whatever sits on its sides: the navigation bar of a phone in landscape, or a camera cutout.
 * Every screen's content column takes it, header included, so nothing lands under the bar.
 */
@Composable
fun Modifier.sideSafeArea(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
