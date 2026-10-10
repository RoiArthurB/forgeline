package fr.arthurbrugiere.forgeline.navigation

import fr.arthurbrugiere.forgeline.core.model.StartTab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * One back stack per top-level tab. The home tab always sits underneath the current tab, so
 * Back walks the current tab's history, then returns to the home tab, then leaves the app.
 *
 * Home is the tab chosen in Settings. Left to Forgeline, it is the Inbox for someone signed in and Trending for someone signed in nowhere (the inbox is only a sign-in wall
 * then, and Trending works signed out). It is decided once, when the session first resolves, and never again: signing
 * in later doesn't move the visitor.
 */
@Stable
class AppNavigator(
    private val stacks: Map<TopLevelDestination, MutableList<NavKey>>,
    private val currentTabState: MutableState<TopLevelDestination>,
    private val homeState: MutableState<TopLevelDestination> = mutableStateOf(SIGNED_IN_HOME),
    private val settledState: MutableState<Boolean> = mutableStateOf(true),
) {
    val currentTab: TopLevelDestination get() = currentTabState.value

    val home: TopLevelDestination get() = homeState.value

    /** Whether [settle] has picked the home tab yet. Until it has, the app shows nothing rather than the wrong tab. */
    val settled: Boolean get() = settledState.value

    /** Picks the home tab once the session is known. A link already opened on the way in keeps its place. */
    fun settle(signedIn: Boolean, startTab: StartTab = StartTab.AUTOMATIC) {
        if (settled) return
        homeState.value = when (startTab) {
            StartTab.AUTOMATIC -> if (signedIn) SIGNED_IN_HOME else SIGNED_OUT_HOME
            StartTab.INBOX -> TopLevelDestination.INBOX
            StartTab.FEED -> TopLevelDestination.FEED
            StartTab.TRENDING -> TopLevelDestination.TRENDING
            StartTab.YOU -> TopLevelDestination.YOU
        }
        if (currentTab == SIGNED_IN_HOME && stacks.getValue(currentTab).size == 1) currentTabState.value = home
        settledState.value = true
    }

    fun backStackOf(tab: TopLevelDestination): List<NavKey> = stacks.getValue(tab)

    fun visibleBackStacks(): List<TopLevelDestination> =
        if (currentTab == home) listOf(home) else listOf(home, currentTab)

    fun selectTab(tab: TopLevelDestination) {
        if (tab == currentTab) {
            val stack = stacks.getValue(tab)
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
        } else {
            currentTabState.value = tab
        }
    }

    fun navigate(key: NavKey) {
        stacks.getValue(currentTab).add(key)
    }

    /** Puts [key] in place of the screen shown: Back from what a form led to doesn't return to the form. */
    fun replaceCurrent(key: NavKey) {
        val stack = stacks.getValue(currentTab)
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack.add(key)
    }

    /** Opens an external link (a tapped notification) on top of the home tab. */
    fun openLink(key: NavKey) {
        currentTabState.value = home
        navigate(key)
    }

    fun goBack() {
        val stack = stacks.getValue(currentTab)
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            currentTab != home -> currentTabState.value = home
        }
    }

    companion object {
        val SIGNED_IN_HOME = TopLevelDestination.INBOX
        val SIGNED_OUT_HOME = TopLevelDestination.TRENDING
    }
}

@Composable
fun rememberAppNavigator(settled: Boolean = false): AppNavigator {
    val stacks = TopLevelDestination.entries.associateWith { rememberNavBackStack(it.root) }
    val currentTab = rememberSaveable { mutableStateOf(AppNavigator.SIGNED_IN_HOME) }
    val home = rememberSaveable { mutableStateOf(AppNavigator.SIGNED_IN_HOME) }
    val settledState = rememberSaveable { mutableStateOf(settled) }
    return remember(stacks, currentTab, home, settledState) { AppNavigator(stacks, currentTab, home, settledState) }
}
