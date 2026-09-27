package fr.arthurbrugiere.forgeline.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * One back stack per top-level tab. The start tab always sits underneath the current tab, so
 * Back walks the current tab's history, then returns to the start tab, then leaves the app.
 */
@Stable
class AppNavigator(
    private val stacks: Map<TopLevelDestination, MutableList<NavKey>>,
    private val currentTabState: MutableState<TopLevelDestination>,
) {
    val currentTab: TopLevelDestination get() = currentTabState.value

    fun backStackOf(tab: TopLevelDestination): List<NavKey> = stacks.getValue(tab)

    fun visibleBackStacks(): List<TopLevelDestination> =
        if (currentTab == START_TAB) listOf(START_TAB) else listOf(START_TAB, currentTab)

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

    /** Opens an external link (a tapped notification) on top of the start tab. */
    fun openLink(key: NavKey) {
        currentTabState.value = START_TAB
        navigate(key)
    }

    fun goBack() {
        val stack = stacks.getValue(currentTab)
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            currentTab != START_TAB -> currentTabState.value = START_TAB
        }
    }

    companion object {
        val START_TAB = TopLevelDestination.INBOX
    }
}

@Composable
fun rememberAppNavigator(): AppNavigator {
    val stacks = TopLevelDestination.entries.associateWith { rememberNavBackStack(it.root) }
    val currentTab = rememberSaveable { mutableStateOf(AppNavigator.START_TAB) }
    return remember(stacks, currentTab) { AppNavigator(stacks, currentTab) }
}
