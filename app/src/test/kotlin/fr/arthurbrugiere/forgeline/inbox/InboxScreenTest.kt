package fr.arthurbrugiere.forgeline.inbox

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import org.junit.Rule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class InboxScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val mention = notificationThread("42", repo = "acme/rocket", title = "Launch fails on cold start", reason = NotificationReason.MENTION)
    private val review = notificationThread("43", repo = "acme/rocket", title = "Add retry", reason = NotificationReason.REVIEW_REQUESTED)
    private val release = notificationThread("9", repo = "octo/tools", title = "v2.0.0", reason = NotificationReason.SUBSCRIBED, number = null)

    /** What Settings holds for the test; every default unless it says otherwise. */
    private var userSettings = fr.arthurbrugiere.forgeline.core.model.UserSettings()

    private fun setContent(state: InboxUiState, prompt: NotificationPrompt? = null) {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(fr.arthurbrugiere.forgeline.ui.LocalUserSettings provides userSettings) {
            InboxScreen(
                state = state,
                onSelectFilter = { events += "filter:$it" },
                onRefresh = { events += "refresh" },
                onOpen = { events += "open:${it.id}" },
                onMarkRead = { events += "read:${it.id}" },
                onMarkUnread = { events += "unread:${it.id}" },
                onMarkDone = { events += "done:${it.id}" },
                onUnsubscribe = { events += "unsubscribe:${it.id}" },
                onErrorShown = {},
                onActionFailureShown = {},
                notificationPrompt = prompt,
                onAllowNotifications = { events += "allow" },
                onUndo = { events += "undo:${it.key.substringAfter('|')}:${it.action}" },
                onToggleSelected = { events += "pick:${it.id}" },
                onSelectAll = { events += "pick-all" },
                onClearSelection = { events += "pick-none" },
                onActOnSelected = { events += "selected:$it" },
                onMarkAllDone = { threads -> events += "all-done:" + threads.joinToString(",") { it.id } },
                nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
            )
        }
        }
    }

    private val grouped = InboxUiState(
        groups = listOf(SectionGroup(InboxSection.NEEDS_YOU, listOf(mention, review)), SectionGroup(InboxSection.OTHERS, listOf(release))),
        syncedAtMillis = 1,
    )

    @Test
    fun what_needs_you_comes_first_led_by_why() {
        setContent(grouped)

        composeRule.onNodeWithText("Needs you").assertIsDisplayed()
        composeRule.onNodeWithText("Mentioned").assertIsDisplayed()
        composeRule.onNodeWithText("Review requested").assertIsDisplayed()
        composeRule.onNodeWithText("acme/\u2060rocket #42").assertIsDisplayed()
        composeRule.onNodeWithText("Launch fails on cold start").assertIsDisplayed()
        composeRule.onAllNodes(hasText("1 hr. ago")).assertCountEquals(2)
    }

    @Test
    fun everything_else_is_led_by_what_it_is() {
        setContent(grouped)

        composeRule.onNodeWithText("Everything else").assertIsDisplayed()
        composeRule.onNodeWithText("Issue").assertIsDisplayed()
        composeRule.onNodeWithText("octo/\u2060tools").assertIsDisplayed()
        composeRule.onNodeWithText("Watching · 1 hr. ago").assertIsDisplayed()
    }

    @Test
    fun everything_else_says_where_a_pull_request_or_issue_stands() {
        // Regression: every pull request read "Pull request", merged or not.
        val merged = notificationThread("7", repo = "octo/tools", type = SubjectType.PULL_REQUEST, reason = NotificationReason.SUBSCRIBED)
            .copy(state = SubjectState.MERGED)
        val open = notificationThread("8", repo = "octo/tools", type = SubjectType.PULL_REQUEST, reason = NotificationReason.SUBSCRIBED)
            .copy(state = SubjectState.OPEN)
        val closed = notificationThread("9", repo = "octo/tools", type = SubjectType.ISSUE, reason = NotificationReason.SUBSCRIBED)
            .copy(state = SubjectState.CLOSED)
        setContent(grouped.copy(groups = listOf(SectionGroup(InboxSection.OTHERS, listOf(merged, open, closed)))))

        composeRule.onNodeWithText("Merged pull request").assertIsDisplayed()
        composeRule.onNodeWithText("Open pull request").assertIsDisplayed()
        composeRule.onNodeWithText("Closed issue").assertIsDisplayed()
        composeRule.onNodeWithText("Pull request").assertDoesNotExist()
    }

    @Test
    fun a_held_action_offers_undo() {
        setContent(grouped.copy(undo = PendingUndo(notificationThread("42").key, InboxAction.DONE, serial = 1)))

        composeRule.onNodeWithText("Marked as done").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        assertThat(events).containsExactly("undo:42:DONE")
    }

    @Test
    fun a_swiped_away_thread_comes_back_in_place_on_undo() {
        var state by mutableStateOf(grouped)
        composeRule.setContent {
            InboxScreen(
                state = state, onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {},
                onMarkDone = { done ->
                    state = state.copy(
                        groups = state.groups.map { g -> g.copy(threads = g.threads - done) },
                        undo = PendingUndo(done.id, InboxAction.DONE, serial = 1),
                    )
                },
                onUnsubscribe = {}, onErrorShown = {}, onActionFailureShown = {},
                onUndo = { events += "undo"; state = grouped },
            )
        }

        composeRule.onNodeWithText("Add retry").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText("Add retry")).assertCountEquals(0)
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        // Back, in place, and not marked done a second time.
        assertThat(events).containsExactly("undo")
        composeRule.onAllNodes(hasText("Marked as done")).assertCountEquals(0)
        val row = composeRule.onNodeWithText("Add retry").fetchSemanticsNode().boundsInRoot
        val heading = composeRule.onNodeWithText("Needs you").fetchSemanticsNode().boundsInRoot
        assertThat(row.left).isLessThan(heading.right)
        composeRule.onNodeWithText("Add retry").assertIsDisplayed()
    }

    @Test
    fun undo_goes_away_once_the_action_is_sent() {
        var state by mutableStateOf(grouped.copy(undo = PendingUndo(notificationThread("42").key, InboxAction.READ, serial = 1)))
        composeRule.setContent {
            InboxScreen(
                state = state, onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {}, onMarkDone = {},
                onUnsubscribe = {}, onErrorShown = {}, onActionFailureShown = {},
            )
        }
        composeRule.onNodeWithText("Marked as read").assertIsDisplayed()

        state = state.copy(undo = null)
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasText("Undo")).assertCountEquals(0)
    }

    @Test
    fun tapping_a_thread_opens_it() {
        setContent(grouped)

        composeRule.onNodeWithText("Add retry").performClick()

        assertThat(events).containsExactly("open:43")
    }

    @Test
    fun swiping_right_marks_read_and_left_marks_done() {
        setContent(grouped)

        composeRule.onNodeWithText("Launch fails on cold start").performTouchInput { swipeRight() }
        composeRule.onNodeWithText("Add retry").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertThat(events).containsExactly("read:42", "done:43").inOrder()
    }

    private val readThread = notificationThread("44", repo = "acme/rocket", title = "Docs typo", reason = NotificationReason.MENTION, unread = false)

    @Test
    fun the_read_swipe_marks_a_read_thread_unread_again() {
        // The read swipe had nothing to do on a thread already read, which is every thread but a few under All.
        setContent(InboxUiState(filter = InboxFilter.ALL, groups = listOf(SectionGroup(InboxSection.NEEDS_YOU, listOf(readThread))), syncedAtMillis = 1))

        composeRule.onNodeWithText("Docs typo").performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertThat(events).containsExactly("unread:44")
        // The row stays, back in place.
        composeRule.onNodeWithText("Docs typo").assertIsDisplayed()
    }

    @Test
    fun the_menu_marks_a_read_thread_unread_and_an_unread_one_read() {
        setContent(InboxUiState(filter = InboxFilter.ALL, groups = listOf(SectionGroup(InboxSection.NEEDS_YOU, listOf(readThread, mention))), syncedAtMillis = 1))

        composeRule.onAllNodes(hasContentDescription("More actions")).onFirst().performClick()
        composeRule.onNodeWithText("Mark as unread").performClick()
        composeRule.onAllNodes(hasContentDescription("More actions"))[1].performClick()
        composeRule.onNodeWithText("Mark as read").performClick()

        assertThat(events).containsExactly("unread:44", "read:42").inOrder()
    }

    @Test
    fun marking_unread_offers_undo() {
        setContent(grouped.copy(undo = PendingUndo("|44", InboxAction.UNREAD, serial = 1)))

        composeRule.onNodeWithText("Marked as unread").assertIsDisplayed()
    }

    @Test
    fun a_long_press_picks_a_thread() {
        setContent(grouped)

        composeRule.onNodeWithText("Add retry").performTouchInput { longClick() }

        assertThat(events).containsExactly("pick:43")
        // Nothing picked yet as far as the screen was told: no bar.
        composeRule.onNodeWithContentDescription("Cancel selection").assertDoesNotExist()
    }

    @Test
    fun while_picking_a_tap_picks_instead_of_opening_and_nothing_swipes() {
        setContent(grouped.copy(selected = setOf(mention.key)))

        composeRule.onNodeWithText("Add retry").performClick()
        composeRule.onNodeWithText("Add retry").performTouchInput { swipeLeft() }
        composeRule.onNodeWithText("Launch fails on cold start").performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertThat(events).containsExactly("pick:43")
        // The one-thread menus step aside.
        composeRule.onAllNodes(hasContentDescription("More actions")).assertCountEquals(0)
    }

    @Test
    fun the_selection_bar_counts_and_acts_on_what_is_picked() {
        setContent(grouped.copy(selected = setOf(mention.key, review.key)))

        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Mark as read").performClick()
        composeRule.onNodeWithContentDescription("Done").performClick()
        composeRule.onNodeWithContentDescription("Select all").performClick()
        composeRule.onNodeWithContentDescription("Cancel selection").performClick()

        assertThat(events).containsExactly("selected:READ", "selected:DONE", "pick-all", "pick-none").inOrder()
    }

    @Test
    fun picked_threads_all_read_are_offered_unread() {
        setContent(InboxUiState(filter = InboxFilter.ALL, groups = listOf(SectionGroup(InboxSection.NEEDS_YOU, listOf(readThread))), syncedAtMillis = 1, selected = setOf(readThread.key)))

        composeRule.onNodeWithContentDescription("Mark as unread").performClick()

        assertThat(events).containsExactly("selected:UNREAD")
    }

    @Test
    fun a_screen_reader_hears_which_threads_are_picked() {
        setContent(grouped.copy(selected = setOf(mention.key)))

        composeRule.onNode(hasText("Launch fails on cold start") and androidx.compose.ui.test.isSelected()).assertExists()
        composeRule.onNode(hasText("Add retry") and androidx.compose.ui.test.isNotSelected()).assertExists()
    }

    @Test
    fun undo_says_how_many_threads_were_marked_read() {
        setContent(grouped.copy(undo = PendingUndo("|42", InboxAction.READ, serial = 1, others = listOf("|43"))))

        composeRule.onNodeWithText("Marked 2 as read").assertIsDisplayed()
    }

    @Test
    fun the_menu_offers_every_action() {
        setContent(grouped)

        composeRule.onAllNodes(hasContentDescription("More actions")).onFirst().performClick()
        composeRule.onNodeWithText("Unsubscribe").performClick()

        assertThat(events).containsExactly("unsubscribe:42")
    }

    @Test
    fun filters_are_chips() {
        setContent(grouped)

        composeRule.onNodeWithText("Participating").performClick()

        assertThat(events).containsExactly("filter:PARTICIPATING")
    }

    @Test
    fun an_empty_unread_inbox_is_all_caught_up() {
        setContent(InboxUiState(syncedAtMillis = 1))

        composeRule.onNodeWithText("You're all caught up").assertIsDisplayed()
    }

    @Test
    fun an_empty_filtered_inbox_says_nothing_matches() {
        setContent(InboxUiState(filter = InboxFilter.ALL, syncedAtMillis = 1))

        composeRule.onNodeWithText("No notifications match this filter.").assertIsDisplayed()
    }

    @Test
    fun a_token_without_the_notifications_scope_is_explained() {
        setContent(InboxUiState(error = ForgeError.Http(403, "Missing the 'notifications' scope.")))

        composeRule.onNodeWithText("Your token can't read notifications", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun asks_for_the_notification_permission_when_needed() {
        setContent(grouped, NotificationPrompt.ASK)

        composeRule.onNodeWithText("Get notified about new activity").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").performClick()
        assertThat(events).containsExactly("allow")
    }

    @Test
    fun once_denied_the_prompt_points_to_settings() {
        setContent(grouped, NotificationPrompt.OPEN_SETTINGS)

        composeRule.onNodeWithText("Open settings").performClick()
        assertThat(events).containsExactly("allow")
    }

    @Test
    fun no_prompt_when_notifications_are_allowed() {
        setContent(grouped)

        composeRule.onAllNodes(hasText("Get notified about new activity")).assertCountEquals(0)
    }

    @Test
    fun the_same_thread_id_on_two_forges_shows_twice() {
        // Regression guard: rows were keyed by thread id alone, and duplicate keys crash the list.
        val github = notificationThread("1", repo = "octo/tools", reason = NotificationReason.SUBSCRIBED).copy(accountId = "github:github.com:me")
        val codeberg = notificationThread("1", repo = "forgejo/forgejo", reason = NotificationReason.SUBSCRIBED)
            .let { it.copy(repo = it.repo.copy(forge = ForgeInstance.Codeberg), accountId = "forgejo:codeberg.org:me") }
        setContent(grouped.copy(groups = listOf(SectionGroup(InboxSection.OTHERS, listOf(github, codeberg))), showForge = true))

        composeRule.onNodeWithText("Codeberg · Watching", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("GitHub · Watching", substring = true).assertIsDisplayed()
    }

    @Test
    fun one_account_pill_starts_on_all_and_names_each_forge() {
        val github = Account(Account.idFor(ForgeInstance.GitHub, "me"), ForgeInstance.GitHub, ForgeUser("me", null, null))
        val codeberg = Account(Account.idFor(ForgeInstance.Codeberg, "me"), ForgeInstance.Codeberg, ForgeUser("me", null, null))
        val picked = mutableListOf<String?>()
        composeRule.setContent {
            InboxScreen(
                state = grouped.copy(accountTabs = listOf(github, codeberg)),
                onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {}, onMarkDone = {}, onUnsubscribe = {},
                onErrorShown = {}, onActionFailureShown = {}, onSelectAccount = { picked += it },
            )
        }

        // One pill in the header's title row; its menu names each account's forge in words (two can share a login).
        composeRule.onNodeWithContentDescription("Account").performClick()
        composeRule.onNodeWithText("@me · GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("@me · Codeberg").performClick()
        composeRule.onNodeWithContentDescription("Account").performClick()
        composeRule.onNodeWithText("All accounts").performClick()

        assertThat(picked).containsExactly(codeberg.id, null).inOrder()
    }

    @Test
    fun screen_readers_hear_which_notifications_are_unread() {
        // Regression: unread was only a colour dot and a bolder title.
        val read = notificationThread("44", repo = "acme/rocket", title = "Already seen", reason = NotificationReason.MENTION, unread = false)
        setContent(InboxUiState(groups = listOf(SectionGroup(InboxSection.NEEDS_YOU, listOf(mention, read))), syncedAtMillis = 1))

        composeRule.onNode(hasText("Launch fails on cold start") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unread")).assertExists()
        composeRule.onNode(hasText("Already seen") and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertDoesNotExist()
    }

    private fun onForge(forge: ForgeInstance, id: String, repo: String) =
        notificationThread(id, repo = repo, title = "Thread $id", reason = NotificationReason.SUBSCRIBED)
            .let { it.copy(repo = it.repo.copy(forge = forge)) }

    private fun others(vararg threads: NotificationThread, showForge: Boolean = true) =
        setContent(InboxUiState(groups = listOf(SectionGroup(InboxSection.OTHERS, threads.toList())), syncedAtMillis = 1, showForge = showForge))

    private fun forgeIcons(name: String) = composeRule.onAllNodes(hasContentDescription(name), useUnmergedTree = true)

    @Test
    fun a_repository_heading_wears_its_forge_after_the_name() {
        others(onForge(ForgeInstance.Codeberg, "1", "octo/tools"), onForge(ForgeInstance.GitHub, "2", "acme/rocket"))

        // Each lone repository: avatar, owner/ and name, then its forge's logo.
        forgeIcons("Codeberg").assertCountEquals(1)
        forgeIcons("GitHub").assertCountEquals(1)
    }

    @Test
    fun an_owner_with_several_repositories_wears_the_forge_on_the_owner_and_on_each_repository() {
        others(
            onForge(ForgeInstance.Codeberg, "1", "octo/tools"),
            onForge(ForgeInstance.Codeberg, "2", "octo/docs"),
        )

        // <avatar> octo <logo>, then "tools <logo>" and "docs <logo>".
        forgeIcons("Codeberg").assertCountEquals(3)
    }

    @Test
    fun the_same_owner_on_two_forges_gets_two_headings() {
        // Regression: threads were grouped by owner name alone, so "acme" on GitHub and on Codeberg shared one heading.
        others(onForge(ForgeInstance.GitHub, "1", "acme/rocket"), onForge(ForgeInstance.Codeberg, "2", "acme/rocket"))

        forgeIcons("GitHub").assertCountEquals(1)
        forgeIcons("Codeberg").assertCountEquals(1)
    }

    @Test
    fun one_forge_alone_draws_no_logos_in_headings() {
        others(onForge(ForgeInstance.GitHub, "1", "acme/rocket"), showForge = false)

        forgeIcons("GitHub").assertCountEquals(0)
    }

    private val watched = InboxUiState(
        groups = listOf(
            SectionGroup(
                InboxSection.OTHERS,
                listOf(
                    notificationThread("50", repo = "octo/tools", title = "Flaky upload", reason = NotificationReason.SUBSCRIBED),
                    notificationThread("51", repo = "octo/tools", title = "Bump the SDK", reason = NotificationReason.SUBSCRIBED),
                    notificationThread("60", repo = "acme/rocket", title = "Fuel gauge drifts", reason = NotificationReason.SUBSCRIBED),
                ),
            ),
        ),
        syncedAtMillis = 1,
    )

    @Test
    fun a_repository_swiped_away_takes_every_thread_under_its_heading() {
        setContent(watched)

        composeRule.onNodeWithText("tools", substring = true).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // Its two threads, and not the other repository's.
        assertThat(events).containsExactly("all-done:50,51")
    }

    @Test
    fun a_repository_heading_is_not_swiped_the_other_way() {
        setContent(watched)

        composeRule.onNodeWithText("tools", substring = true).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertThat(events).isEmpty()
    }

    @Test
    fun a_screen_reader_can_mark_a_whole_repository_done() {
        setContent(watched)

        val actions = composeRule.onNodeWithText("tools", substring = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        actions.single { it.label == "Mark all as done" }.action()

        assertThat(events).containsExactly("all-done:50,51")
    }

    @Test
    fun undo_says_how_many_threads_went_with_a_repository() {
        val keys = watched.groups.single().threads.take(2).map { it.key }
        setContent(watched.copy(undo = PendingUndo(keys[0], InboxAction.DONE, serial = 1, others = keys.drop(1))))

        composeRule.onNodeWithText("Marked 2 as done").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
    }

    @Test
    fun the_two_swipes_can_be_swapped_in_settings() {
        userSettings = userSettings.copy(inboxSwipeRight = fr.arthurbrugiere.forgeline.core.model.SwipeAction.DONE, inboxSwipeLeft = fr.arthurbrugiere.forgeline.core.model.SwipeAction.MARK_READ)
        setContent(grouped)

        composeRule.onNodeWithText("Launch fails on cold start").performTouchInput { swipeRight() }
        composeRule.onNodeWithText("Add retry").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertThat(events).containsExactly("done:42", "read:43").inOrder()
    }

    @Test
    fun a_swipe_set_to_nothing_does_nothing() {
        userSettings = userSettings.copy(inboxSwipeRight = fr.arthurbrugiere.forgeline.core.model.SwipeAction.NONE, inboxSwipeLeft = fr.arthurbrugiere.forgeline.core.model.SwipeAction.NONE)
        setContent(grouped)

        composeRule.onNodeWithText("Launch fails on cold start").performTouchInput { swipeRight() }
        composeRule.onNodeWithText("Add retry").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertThat(events).isEmpty()
        composeRule.onNodeWithText("Add retry").assertIsDisplayed()
    }

    @Test
    fun a_repository_is_swiped_away_toward_the_side_that_means_done() {
        userSettings = userSettings.copy(inboxSwipeRight = fr.arthurbrugiere.forgeline.core.model.SwipeAction.DONE, inboxSwipeLeft = fr.arthurbrugiere.forgeline.core.model.SwipeAction.MARK_READ)
        setContent(watched)

        composeRule.onNodeWithText("tools", substring = true).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertThat(events).isEmpty()

        composeRule.onNodeWithText("tools", substring = true).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertThat(events).containsExactly("all-done:50,51")
    }

    @Test
    fun with_no_swipe_meaning_done_a_repository_is_not_swiped_away() {
        userSettings = userSettings.copy(inboxSwipeLeft = fr.arthurbrugiere.forgeline.core.model.SwipeAction.NONE)
        setContent(watched)

        composeRule.onNodeWithText("tools", substring = true).performTouchInput { swipeLeft() }
        composeRule.onNodeWithText("tools", substring = true).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertThat(events).isEmpty()
    }
}
