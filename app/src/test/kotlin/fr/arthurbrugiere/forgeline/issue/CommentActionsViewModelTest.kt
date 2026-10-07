package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.core.model.Reaction
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.InMemoryConversationDao
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock

/** Quoting, rewriting and deleting comments, and hearing of a conversation edited from its form. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommentActionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeIssueApi()
    private val accounts = FakeAccountRepository()
    private val repository = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, InMemoryConversationDao(), Clock.systemUTC())
    private val ref = IssueRef(RepoId("octo", "repo"), 7)
    private val saved = SavedStateHandle()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    /** A conversation opened by octocat, with a comment of theirs and one of the reader's ("me"). */
    private suspend fun TestScope.opened(signedIn: Boolean = true, nextPage: Int? = null): IssueViewModel {
        if (signedIn) accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Theirs"), comment(2, "Mine", login = "me")), nextPage)
        return IssueViewModel(ref, repository, saved, IssueDrafts()).also { advanceUntilIdle() }
    }

    private val IssueViewModel.comments get() = state.value.items.filterIsInstance<TimelineItem.Comment>()

    @Test
    fun one_s_own_comments_can_be_rewritten_and_deleted_and_nobody_else_s() = test {
        val state = opened().state.value
        val (theirs, mine) = state.items.filterIsInstance<TimelineItem.Comment>()

        assertThat(state.me).isEqualTo("me")
        assertThat(state.canEdit(mine)).isTrue()
        assertThat(state.canDelete(mine)).isTrue()
        assertThat(state.canEdit(theirs)).isFalse()
        assertThat(state.canDelete(theirs)).isFalse()
        // The conversation is octocat's: its title and text are not the reader's to change.
        assertThat(state.canEditIssue).isFalse()
    }

    @Test
    fun logins_are_the_same_whatever_their_case() = test {
        // Forges keep the case a login was typed in; the account may hold another.
        api.issues[ref] = issueDetails(ref).copy(author = ForgeUser("Me", null, null))
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        api.pages[ref to 1] = TimelinePage(listOf(comment(2, "Mine", login = "ME")), null)
        val state = IssueViewModel(ref, repository, saved, IssueDrafts()).also { advanceUntilIdle() }.state.value

        assertThat(state.canEdit(state.items.single() as TimelineItem.Comment)).isTrue()
        assertThat(state.canEditIssue).isTrue()
    }

    @Test
    fun whoever_can_write_to_the_repository_removes_any_comment_and_edits_the_issue_but_rewrites_only_their_own() = test {
        api.access[ref.repo] = RepoAccess.WRITE
        val state = opened().state.value
        val theirs = state.items.first() as TimelineItem.Comment

        assertThat(state.canDelete(theirs)).isTrue()
        assertThat(state.canEditIssue).isTrue()
        assertThat(state.canEdit(theirs)).isFalse()
    }

    @Test
    fun signed_out_nothing_can_be_changed() = test {
        val state = opened(signedIn = false).state.value

        assertThat(state.me).isNull()
        assertThat(state.items.filterIsInstance<TimelineItem.Comment>().none { state.canEdit(it) || state.canDelete(it) }).isTrue()
        assertThat(state.canEditIssue).isFalse()
    }

    @Test
    fun a_quote_starts_the_reply_and_takes_the_reader_to_it() = test {
        val viewModel = opened()

        viewModel.quote("First line\n\nSecond line\n")

        val state = viewModel.state.value
        // Every line is quoted, the empty one between included, and the reply starts a paragraph below.
        assertThat(state.draft).isEqualTo("> First line\n>\n> Second line\n\n")
        assertThat(state.scrollTo).isEqualTo(ScrollTarget.End)
        assertThat(state.draftPlaced).isEqualTo(1)
    }

    @Test
    fun a_quote_goes_after_what_is_already_written() = test {
        val viewModel = opened()
        viewModel.draftChanged("I agree.  \n")

        viewModel.quote("And this?")

        assertThat(viewModel.state.value.draft).isEqualTo("I agree.\n\n> And this?\n\n")
    }

    @Test
    fun quoting_nothing_writes_nothing() = test {
        val viewModel = opened()

        viewModel.quote("  \n ")

        assertThat(viewModel.state.value.draft).isEmpty()
        assertThat(viewModel.state.value.scrollTo).isNull()
    }

    @Test
    fun a_quote_is_part_of_the_draft_kept_when_the_app_is_stopped() = test {
        opened().quote("Quoted")

        assertThat(IssueViewModel(ref, repository, saved, IssueDrafts()).state.value.draft).isEqualTo("> Quoted\n\n")
    }

    @Test
    fun rewriting_a_comment_puts_its_text_in_the_reader_s_turn_and_sets_the_draft_aside() = test {
        val viewModel = opened()
        viewModel.draftChanged("Half a thought")

        viewModel.startEditing(2)

        val state = viewModel.state.value
        assertThat(state.editing).isEqualTo(2)
        assertThat(state.draft).isEqualTo("Mine")
        assertThat(state.scrollTo).isEqualTo(ScrollTarget.End)
        assertThat(state.draftPlaced).isEqualTo(1)
    }

    @Test
    fun a_rewritten_comment_is_saved_in_place_and_the_draft_comes_back() = test {
        val viewModel = opened()
        viewModel.draftChanged("Half a thought")
        viewModel.startEditing(2)

        viewModel.draftChanged("  Mine, corrected ")
        viewModel.sendComment()
        assertThat(viewModel.state.value.isCommenting).isTrue()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(api.commentChanges).containsExactly("edit octo/repo#7 comment 2: Mine, corrected")
        // Nothing was added: the comment changed where it stands.
        assertThat(api.posted).isEmpty()
        assertThat(viewModel.comments.map { it.body }).containsExactly("Theirs", "Mine, corrected").inOrder()
        assertThat(state.issue?.comments).isEqualTo(issueDetails(ref).comments)
        assertThat(state.editing).isNull()
        assertThat(state.draft).isEqualTo("Half a thought")
        assertThat(state.isCommenting).isFalse()
    }

    @Test
    fun a_rewrite_the_forge_refuses_stays_written_and_says_why() = test {
        val viewModel = opened()
        viewModel.startEditing(2)
        viewModel.draftChanged("Mine, corrected")
        api.editFailure = ForgeError.Network

        viewModel.sendComment()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.editing).isEqualTo(2)
        assertThat(state.draft).isEqualTo("Mine, corrected")
        assertThat(state.commentError).isEqualTo(ForgeError.Network)
        assertThat(viewModel.comments.map { it.body }).containsExactly("Theirs", "Mine").inOrder()
    }

    @Test
    fun giving_up_a_rewrite_leaves_the_comment_and_gives_the_draft_back() = test {
        val viewModel = opened()
        viewModel.draftChanged("Half a thought")
        viewModel.startEditing(2)
        viewModel.draftChanged("Changed my mind")

        viewModel.cancelEditing()

        assertThat(viewModel.state.value.editing).isNull()
        assertThat(viewModel.state.value.draft).isEqualTo("Half a thought")
        assertThat(api.commentChanges).isEmpty()
    }

    @Test
    fun what_is_typed_while_rewriting_never_becomes_the_draft_kept() = test {
        // Regression guard: stopped mid-rewrite, the app would otherwise come back offering the old comment's text
        // as a new comment.
        val viewModel = opened()
        viewModel.draftChanged("Half a thought")
        viewModel.startEditing(2)
        viewModel.draftChanged("Mine, corrected")

        assertThat(IssueViewModel(ref, repository, saved, IssueDrafts()).state.value.draft).isEqualTo("Half a thought")
    }

    @Test
    fun moving_from_one_rewrite_to_another_keeps_the_draft_set_aside() = test {
        api.pages[ref to 1] = TimelinePage(listOf(comment(2, "Mine", login = "me"), comment(3, "Mine too", login = "me")), null)
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        api.issues[ref] = issueDetails(ref)
        val viewModel = IssueViewModel(ref, repository, saved, IssueDrafts()).also { advanceUntilIdle() }
        viewModel.draftChanged("Half a thought")

        viewModel.startEditing(2)
        viewModel.startEditing(3)
        assertThat(viewModel.state.value.draft).isEqualTo("Mine too")
        viewModel.cancelEditing()

        assertThat(viewModel.state.value.draft).isEqualTo("Half a thought")
    }

    @Test
    fun a_comment_that_is_not_there_can_t_be_rewritten() = test {
        val viewModel = opened()

        viewModel.startEditing(99)

        assertThat(viewModel.state.value.editing).isNull()
    }

    @Test
    fun a_deleted_comment_leaves_the_conversation() = test {
        val viewModel = opened()

        viewModel.deleteComment(2)
        // Still there until the forge has let it go.
        assertThat(viewModel.comments).hasSize(2)
        advanceUntilIdle()

        assertThat(api.commentChanges).containsExactly("delete octo/repo#7 comment 2")
        assertThat(viewModel.comments.map { it.body }).containsExactly("Theirs")
        assertThat(viewModel.state.value.issue?.comments).isEqualTo(issueDetails(ref).comments - 1)
        assertThat(viewModel.state.value.deleteError).isNull()
    }

    @Test
    fun a_comment_the_forge_keeps_stays_and_the_reader_is_told_once() = test {
        val viewModel = opened()
        api.editFailure = ForgeError.Http(403, "not yours")

        viewModel.deleteComment(1)
        advanceUntilIdle()

        assertThat(viewModel.comments).hasSize(2)
        assertThat(viewModel.state.value.deleteError).isEqualTo(ForgeError.Http(403, "not yours"))
        viewModel.deleteErrorShown()
        assertThat(viewModel.state.value.deleteError).isNull()
    }

    @Test
    fun deleting_the_comment_being_rewritten_ends_the_rewrite() = test {
        val viewModel = opened()
        viewModel.draftChanged("Half a thought")
        viewModel.startEditing(2)

        viewModel.deleteComment(2)
        advanceUntilIdle()

        assertThat(viewModel.state.value.editing).isNull()
        assertThat(viewModel.state.value.draft).isEqualTo("Half a thought")
    }

    @Test
    fun a_conversation_edited_from_its_form_shows_its_new_title_without_asking_the_forge() = test {
        val viewModel = opened()
        val asked = api.calls.size

        repository.edit(ref, "A better title", "A better text")
        advanceUntilIdle()

        assertThat(viewModel.state.value.issue?.title).isEqualTo("A better title")
        assertThat(viewModel.state.value.issue?.body).isEqualTo("A better text")
        // One call: the edit itself.
        assertThat(api.calls.drop(asked)).containsExactly("edit:octo/repo#7")
    }

    @Test
    fun another_conversation_s_edit_changes_nothing_here() = test {
        val viewModel = opened()
        val other = IssueRef(ref.repo, 8)
        api.issues[other] = issueDetails(other)
        repository.issue(other)

        repository.edit(other, "Another title", "")
        advanceUntilIdle()

        assertThat(viewModel.state.value.issue?.title).isEqualTo(issueDetails(ref).title)
    }

    @Test
    fun a_reaction_shows_on_the_comment_with_everyone_s() = test {
        val viewModel = opened()
        api.reacted[ref to 1L] = mutableListOf("alice" to Reaction.THUMBS_UP)

        viewModel.react(1, Reaction.THUMBS_UP)
        // Nothing is shown before the forge has answered: the count is its own.
        assertThat(viewModel.comments.first().reactions).isEmpty()
        advanceUntilIdle()

        assertThat(viewModel.comments.first().reactions).containsExactly(Reaction.THUMBS_UP, 2)
        assertThat(viewModel.comments.last().reactions).isEmpty()
    }

    @Test
    fun reacting_again_takes_the_reaction_back() = test {
        val viewModel = opened()
        viewModel.react(null, Reaction.HEART)
        advanceUntilIdle()
        assertThat(viewModel.state.value.issue?.reactions).containsExactly(Reaction.HEART, 1)

        viewModel.react(null, Reaction.HEART)
        advanceUntilIdle()

        assertThat(viewModel.state.value.issue?.reactions).isEmpty()
    }

    @Test
    fun a_reaction_tapped_twice_before_the_forge_answers_is_sent_once() = test {
        // Sent twice, the second would take back what the first gave.
        val viewModel = opened()

        viewModel.react(1, Reaction.HEART)
        viewModel.react(1, Reaction.HEART)
        advanceUntilIdle()

        assertThat(api.calls.count { it.startsWith("react:") }).isEqualTo(1)
        assertThat(viewModel.comments.first().reactions).containsExactly(Reaction.HEART, 1)
        // Once it has answered, the next tap goes through.
        viewModel.react(1, Reaction.HEART)
        advanceUntilIdle()
        assertThat(viewModel.comments.first().reactions).isEmpty()
    }

    @Test
    fun two_comments_can_be_reacted_to_at_once() = test {
        val viewModel = opened()

        viewModel.react(1, Reaction.HEART)
        viewModel.react(2, Reaction.EYES)
        advanceUntilIdle()

        assertThat(viewModel.comments.map { it.reactions }).containsExactly(mapOf(Reaction.HEART to 1), mapOf(Reaction.EYES to 1)).inOrder()
    }

    @Test
    fun a_reaction_the_forge_refuses_is_said_once_and_can_be_tried_again() = test {
        val viewModel = opened()
        api.reactionFailure = ForgeError.Network

        viewModel.react(1, Reaction.HEART)
        advanceUntilIdle()

        assertThat(viewModel.state.value.reactionError).isEqualTo(ForgeError.Network)
        assertThat(viewModel.comments.first().reactions).isEmpty()
        viewModel.reactionErrorShown()
        assertThat(viewModel.state.value.reactionError).isNull()
        api.reactionFailure = null
        viewModel.react(1, Reaction.HEART)
        advanceUntilIdle()
        assertThat(viewModel.comments.first().reactions).containsExactly(Reaction.HEART, 1)
    }
}
