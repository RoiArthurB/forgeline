package fr.arthurbrugiere.forgeline.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppNavigatorTest {
    private fun navigator(settled: Boolean = true): AppNavigator = AppNavigator(
        stacks = TopLevelDestination.entries.associateWith { mutableStateListOf<NavKey>(it.root) },
        currentTabState = mutableStateOf(AppNavigator.SIGNED_IN_HOME),
        settledState = mutableStateOf(settled),
    )

    @Test
    fun starts_on_the_inbox() {
        val navigator = navigator()

        assertThat(navigator.currentTab).isEqualTo(TopLevelDestination.INBOX)
        assertThat(navigator.visibleBackStacks()).containsExactly(TopLevelDestination.INBOX)
    }

    @Test
    fun signed_in_settles_on_the_inbox() {
        val navigator = navigator(settled = false)

        navigator.settle(signedIn = true)

        assertThat(navigator.currentTab).isEqualTo(TopLevelDestination.INBOX)
        assertThat(navigator.home).isEqualTo(TopLevelDestination.INBOX)
    }

    @Test
    fun signed_in_nowhere_settles_on_trending_and_back_returns_there() {
        val navigator = navigator(settled = false)

        navigator.settle(signedIn = false)

        assertThat(navigator.currentTab).isEqualTo(TopLevelDestination.TRENDING)
        assertThat(navigator.visibleBackStacks()).containsExactly(TopLevelDestination.TRENDING)
        navigator.selectTab(TopLevelDestination.FEED)
        navigator.goBack()
        assertThat(navigator.currentTab).isEqualTo(TopLevelDestination.TRENDING)
    }

    @Test
    fun the_home_is_decided_once_so_signing_in_later_does_not_move_the_visitor() {
        val navigator = navigator(settled = false)
        navigator.settle(signedIn = false)

        navigator.settle(signedIn = true)

        assertThat(navigator.currentTab).isEqualTo(TopLevelDestination.TRENDING)
        assertThat(navigator.home).isEqualTo(TopLevelDestination.TRENDING)
    }

    @Test
    fun a_link_opened_before_settling_stays_open() {
        val navigator = navigator(settled = false)
        val issue = IssueRoute("github.com", "acme", "rocket", 42)
        navigator.openLink(issue)

        navigator.settle(signedIn = false)

        assertThat(navigator.currentTab).isEqualTo(TopLevelDestination.INBOX)
        assertThat(navigator.backStackOf(TopLevelDestination.INBOX)).containsExactly(InboxRoute, issue).inOrder()
    }

    @Test
    fun selecting_another_tab_keeps_the_inbox_underneath() {
        val navigator = navigator()

        navigator.selectTab(TopLevelDestination.TRENDING)

        assertThat(navigator.currentTab).isEqualTo(TopLevelDestination.TRENDING)
        assertThat(navigator.visibleBackStacks())
            .containsExactly(TopLevelDestination.INBOX, TopLevelDestination.TRENDING).inOrder()
    }

    @Test
    fun navigate_pushes_onto_the_current_tab_only() {
        val navigator = navigator()
        navigator.selectTab(TopLevelDestination.YOU)

        navigator.navigate(SettingsRoute)

        assertThat(navigator.backStackOf(TopLevelDestination.YOU)).containsExactly(YouRoute, SettingsRoute).inOrder()
        assertThat(navigator.backStackOf(TopLevelDestination.INBOX)).containsExactly(InboxRoute)
    }

    @Test
    fun back_pops_within_the_tab_first() {
        val navigator = navigator()
        navigator.selectTab(TopLevelDestination.YOU)
        navigator.navigate(SettingsRoute)

        navigator.goBack()

        assertThat(navigator.currentTab).isEqualTo(TopLevelDestination.YOU)
        assertThat(navigator.backStackOf(TopLevelDestination.YOU)).containsExactly(YouRoute)
    }

    @Test
    fun back_from_a_tab_root_returns_to_the_inbox() {
        val navigator = navigator()
        navigator.selectTab(TopLevelDestination.FEED)

        navigator.goBack()

        assertThat(navigator.currentTab).isEqualTo(TopLevelDestination.INBOX)
    }

    @Test
    fun switching_tabs_preserves_each_tab_history() {
        val navigator = navigator()
        navigator.selectTab(TopLevelDestination.YOU)
        navigator.navigate(SettingsRoute)

        navigator.selectTab(TopLevelDestination.TRENDING)
        navigator.selectTab(TopLevelDestination.YOU)

        assertThat(navigator.backStackOf(TopLevelDestination.YOU)).containsExactly(YouRoute, SettingsRoute).inOrder()
    }

    @Test
    fun reselecting_the_current_tab_pops_it_to_its_root() {
        val navigator = navigator()
        navigator.selectTab(TopLevelDestination.YOU)
        navigator.navigate(SettingsRoute)
        navigator.navigate(CreditsRoute)

        navigator.selectTab(TopLevelDestination.YOU)

        assertThat(navigator.backStackOf(TopLevelDestination.YOU)).containsExactly(YouRoute)
    }

    @Test
    fun an_opened_link_lands_on_the_inbox_so_back_returns_to_it() {
        val navigator = navigator()
        navigator.selectTab(TopLevelDestination.TRENDING)
        val issue = IssueRoute("github.com", "acme", "rocket", 42)

        navigator.openLink(issue)

        assertThat(navigator.currentTab).isEqualTo(TopLevelDestination.INBOX)
        assertThat(navigator.backStackOf(TopLevelDestination.INBOX)).containsExactly(InboxRoute, issue).inOrder()
        navigator.goBack()
        assertThat(navigator.backStackOf(TopLevelDestination.INBOX)).containsExactly(InboxRoute)
    }

    @Test
    fun replacing_the_current_screen_leaves_back_pointing_past_it() {
        // A form that opened something: Back from the result returns to where the form was opened, not to the form.
        val navigator = navigator()
        navigator.selectTab(TopLevelDestination.YOU)
        navigator.navigate(SettingsRoute)
        navigator.navigate(CreditsRoute)

        navigator.replaceCurrent(SignInRoute)

        assertThat(navigator.backStackOf(TopLevelDestination.YOU)).containsExactly(YouRoute, SettingsRoute, SignInRoute).inOrder()
    }

    @Test
    fun replacing_never_removes_a_tab_s_root() {
        val navigator = navigator()
        navigator.selectTab(TopLevelDestination.YOU)

        navigator.replaceCurrent(SettingsRoute)

        assertThat(navigator.backStackOf(TopLevelDestination.YOU)).containsExactly(YouRoute, SettingsRoute).inOrder()
    }
}
