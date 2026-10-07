package fr.arthurbrugiere.forgeline.issue

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.draft.Draft
import fr.arthurbrugiere.forgeline.core.data.draft.DraftStore
import fr.arthurbrugiere.forgeline.core.data.draft.InMemoryDraftStore
import fr.arthurbrugiere.forgeline.core.data.draft.commentDraftKey
import fr.arthurbrugiere.forgeline.core.data.draft.issueDraftKey
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
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

/** A comment or an issue being written is still there after its screen was left, and after the app was closed. */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeIssueApi()
    private val accounts = FakeAccountRepository()
    private val repository = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, InMemoryConversationDao(), Clock.systemUTC())
    private val ref = IssueRef(RepoId("octo", "repo"), 7)
    private val disk = InMemoryDraftStore()

    /** The app started again: nothing is known before it is read, as from a file. */
    private class Relaunched(private val disk: DraftStore) : DraftStore by disk {
        private val read = mutableSetOf<String>()

        override fun peek(key: String): Draft? = if (key in read) disk.peek(key) else null

        override suspend fun read(key: String): Draft? = disk.read(key).also { read += key }

        override fun write(key: String, draft: Draft?) {
            read += key
            disk.write(key, draft)
        }
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(2, "Mine", login = "me")), null)
        block()
    }

    /** The conversation's screen opened anew: nothing saved with it, only what the drafts hold. */
    private fun TestScope.conversation(drafts: IssueDrafts) = IssueViewModel(ref, repository, SavedStateHandle(), drafts).also { advanceUntilIdle() }

    @Test
    fun a_comment_being_written_is_there_when_the_conversation_is_opened_again() = test {
        val drafts = IssueDrafts(disk)
        conversation(drafts).draftChanged("Half a thought")

        val reopened = IssueViewModel(ref, repository, SavedStateHandle(), drafts)

        // At once, before anything is asked: the screen opens on it.
        assertThat(reopened.state.value.draft).isEqualTo("Half a thought")
    }

    @Test
    fun a_comment_being_written_comes_back_after_the_app_was_closed() = test {
        conversation(IssueDrafts(disk)).draftChanged("Half a thought")

        val relaunched = conversation(IssueDrafts(Relaunched(disk)))

        assertThat(relaunched.state.value.draft).isEqualTo("Half a thought")
        // Put there, not typed: the cursor goes after it.
        assertThat(relaunched.state.value.draftPlaced).isEqualTo(1)
    }

    @Test
    fun what_the_reader_started_writing_is_not_replaced_by_what_comes_back_late() = test {
        conversation(IssueDrafts(disk)).draftChanged("From before")
        val relaunched = IssueViewModel(ref, repository, SavedStateHandle(), IssueDrafts(Relaunched(disk)))

        relaunched.draftChanged("Typed first")
        advanceUntilIdle()

        assertThat(relaunched.state.value.draft).isEqualTo("Typed first")
    }

    @Test
    fun each_conversation_has_its_own_draft() = test {
        val drafts = IssueDrafts(disk)
        val other = IssueRef(ref.repo, 8)
        api.issues[other] = issueDetails(other)
        conversation(drafts).draftChanged("For #7")

        assertThat(IssueViewModel(other, repository, SavedStateHandle(), drafts).also { advanceUntilIdle() }.state.value.draft).isEmpty()
        assertThat(conversation(drafts).state.value.draft).isEqualTo("For #7")
    }

    @Test
    fun a_comment_sent_leaves_no_draft_behind() = test {
        val drafts = IssueDrafts(disk)
        val viewModel = conversation(drafts)
        viewModel.draftChanged("Thanks")

        viewModel.sendComment()
        advanceUntilIdle()

        assertThat(disk.peek(commentDraftKey(ref))).isNull()
        assertThat(conversation(IssueDrafts(Relaunched(disk))).state.value.draft).isEmpty()
    }

    @Test
    fun a_comment_that_was_not_sent_is_still_kept() = test {
        val drafts = IssueDrafts(disk)
        val viewModel = conversation(drafts)
        viewModel.draftChanged("Thanks")
        api.commentFailure = ForgeError.Network

        viewModel.sendComment()
        advanceUntilIdle()

        assertThat(conversation(IssueDrafts(Relaunched(disk))).state.value.draft).isEqualTo("Thanks")
    }

    @Test
    fun a_comment_emptied_by_hand_leaves_no_draft_behind() = test {
        val viewModel = conversation(IssueDrafts(disk))
        viewModel.draftChanged("Never mind")

        viewModel.draftChanged("")

        assertThat(disk.peek(commentDraftKey(ref))).isNull()
    }

    @Test
    fun a_comment_being_rewritten_is_never_kept_as_the_draft() = test {
        // Regression guard: leaving mid-rewrite would otherwise come back offering the old comment as a new one.
        val drafts = IssueDrafts(disk)
        val viewModel = conversation(drafts)
        viewModel.draftChanged("Half a thought")

        viewModel.startEditing(2)
        viewModel.draftChanged("Mine, corrected")

        assertThat(disk.peek(commentDraftKey(ref))).isEqualTo(Draft(body = "Half a thought"))
        assertThat(conversation(drafts).state.value.draft).isEqualTo("Half a thought")
    }

    @Test
    fun a_draft_coming_back_late_does_not_land_in_a_rewrite() = test {
        conversation(IssueDrafts(disk)).draftChanged("From before")
        // The conversation is kept from just now, so its comments are there the moment the screen opens.
        val relaunched = IssueViewModel(ref, repository, SavedStateHandle(), IssueDrafts(Relaunched(disk)))

        relaunched.startEditing(2)
        relaunched.draftChanged("")
        advanceUntilIdle()

        // Emptied while rewriting: still the rewrite's field, not the place for the old draft.
        assertThat(relaunched.state.value.editing).isEqualTo(2)
        assertThat(relaunched.state.value.draft).isEmpty()
        // It was only set aside by not being loaded: giving the rewrite up leaves the field empty, and the draft on disk.
        assertThat(disk.peek(commentDraftKey(ref))).isEqualTo(Draft(body = "From before"))
    }

    @Test
    fun a_quote_is_kept_like_anything_written() = test {
        conversation(IssueDrafts(disk)).quote("Quoted")

        assertThat(conversation(IssueDrafts(Relaunched(disk))).state.value.draft).isEqualTo("> Quoted\n\n")
    }

    private val repo = ref.repo

    @Test
    fun an_issue_being_written_is_there_when_the_form_is_opened_again() = test {
        val drafts = IssueDrafts(disk)
        NewIssueViewModel(repo, repository, drafts).apply {
            titleChanged("Crash")
            bodyChanged("Steps")
        }

        val reopened = NewIssueViewModel(repo, repository, drafts)

        assertThat(reopened.state.value.title).isEqualTo("Crash")
        assertThat(reopened.state.value.body).isEqualTo("Steps")
    }

    @Test
    fun an_issue_being_written_comes_back_after_the_app_was_closed() = test {
        NewIssueViewModel(repo, repository, IssueDrafts(disk)).apply {
            titleChanged("Crash")
            bodyChanged("Steps")
        }

        val relaunched = NewIssueViewModel(repo, repository, IssueDrafts(Relaunched(disk)))
        assertThat(relaunched.state.value.title).isEmpty()
        advanceUntilIdle()

        assertThat(relaunched.state.value.title).isEqualTo("Crash")
        assertThat(relaunched.state.value.body).isEqualTo("Steps")
        assertThat(relaunched.state.value.canSend).isTrue()
    }

    @Test
    fun a_title_typed_before_the_old_issue_comes_back_is_kept_as_typed() = test {
        NewIssueViewModel(repo, repository, IssueDrafts(disk)).titleChanged("From before")
        val relaunched = NewIssueViewModel(repo, repository, IssueDrafts(Relaunched(disk)))

        relaunched.titleChanged("Typed first")
        advanceUntilIdle()

        assertThat(relaunched.state.value.title).isEqualTo("Typed first")
    }

    @Test
    fun an_issue_opened_leaves_no_draft_behind() = test {
        val viewModel = NewIssueViewModel(repo, repository, IssueDrafts(disk))
        viewModel.titleChanged("Crash")

        viewModel.send()
        advanceUntilIdle()

        assertThat(disk.peek(issueDraftKey(repo))).isNull()
    }

    @Test
    fun editing_a_conversation_neither_reads_nor_touches_the_issue_being_written() = test {
        disk.write(issueDraftKey(repo), Draft("Not opened yet", "Still thinking"))
        repository.issue(ref)

        val editing = NewIssueViewModel(repo, repository, IssueDrafts(Relaunched(disk)), ref)
        advanceUntilIdle()
        editing.bodyChanged("Changed")

        assertThat(editing.state.value.title).isEqualTo(issueDetails(ref).title)
        assertThat(disk.peek(issueDraftKey(repo))).isEqualTo(Draft("Not opened yet", "Still thinking"))
    }

    @Test
    fun a_duplicate_started_from_a_conversation_is_the_draft_the_form_opens_on() = test {
        val drafts = IssueDrafts(disk)

        conversation(drafts).duplicate()

        val form = NewIssueViewModel(repo, repository, drafts)
        assertThat(form.state.value.title).isEqualTo(issueDetails(ref).title)
        assertThat(form.state.value.body).isEqualTo(issueDetails(ref).body)
    }
}
