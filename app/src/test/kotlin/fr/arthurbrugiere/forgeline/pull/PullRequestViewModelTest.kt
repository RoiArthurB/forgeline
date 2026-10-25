package fr.arthurbrugiere.forgeline.pull

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.pull.DefaultPullRequestRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.ThreadComment
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakePullRequestApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PullRequestViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakePullRequestApi().apply { checks = listOf(Check("build", CheckState.SUCCESS, null, null)) }
    private val accounts = FakeAccountRepository()
    private val repository = DefaultPullRequestRepository(FakeForgeClients(pulls = api), accounts)
    private val pull = IssueRef(RepoId("octo", "tools"), 88, isPullRequest = true)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun viewModel() = PullRequestViewModel(pull, repository, accounts)

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")

    @Test
    fun signed_out_the_checks_are_read_and_nothing_about_merging_is_asked() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.checks!!.single().name).isEqualTo("build")
        assertThat(viewModel.state.value.signedIn).isFalse()
        assertThat(viewModel.state.value.mergeInfo).isNull()
        // What was said on lines is read too: it is public.
        assertThat(api.calls).containsExactly("checks:octo/tools#88", "threads:octo/tools#88")
    }

    @Test
    fun signing_in_on_the_forge_tells_whether_it_can_be_merged() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        // An account somewhere else changes nothing here.
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "tok")
        advanceUntilIdle()
        assertThat(viewModel.state.value.signedIn).isFalse()

        signIn()
        advanceUntilIdle()
        assertThat(viewModel.state.value.signedIn).isTrue()
        assertThat(viewModel.state.value.mergeInfo).isEqualTo(api.mergeInfo)
    }

    @Test
    fun checks_that_cannot_be_read_leave_the_rest_alone() = test {
        api.failure = ForgeError.Network
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.checks).isNull()
    }

    @Test
    fun a_merge_is_told_once_it_went_through() = test {
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.merge(MergeChoice(MergeMethod.SQUASH))
        assertThat(viewModel.state.value.isMerging).isTrue()
        // A second tap while it is on its way sends nothing more.
        viewModel.merge(MergeChoice(MergeMethod.SQUASH))
        advanceUntilIdle()

        assertThat(api.calls.filter { it.startsWith("merge:") }).containsExactly("merge:octo/tools#88:SQUASH")
        assertThat(viewModel.state.value.isMerging).isFalse()
        assertThat(viewModel.state.value.done?.first).isEqualTo(PullDone.MERGED)
    }

    @Test
    fun a_merge_takes_its_branch_along_when_asked() = test {
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.merge(MergeChoice(MergeMethod.MERGE, deleteBranch = true))
        advanceUntilIdle()

        assertThat(api.calls.filter { it.startsWith("merge:") }).containsExactly("merge:octo/tools#88:MERGE:delete-branch")
    }

    @Test
    fun a_merge_set_for_when_the_checks_pass_is_told_apart_and_can_be_taken_back() = test {
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.merge(MergeChoice(MergeMethod.SQUASH, whenChecksPass = true))
        advanceUntilIdle()
        assertThat(viewModel.state.value.done?.first).isEqualTo(PullDone.SCHEDULED)
        // Read again: the forge now says it will merge it by itself.
        assertThat(viewModel.state.value.mergeInfo?.mergesWhenChecksPass).isTrue()

        viewModel.cancelMergeWhenChecksPass()
        advanceUntilIdle()

        assertThat(api.calls.filter { it.startsWith("merge:") || it.startsWith("cancel") })
            .containsExactly("merge:octo/tools#88:SQUASH:when-checks-pass", "cancelMergeWhenChecksPass:octo/tools#88").inOrder()
        assertThat(viewModel.state.value.done?.first).isEqualTo(PullDone.UNSCHEDULED)
        assertThat(viewModel.state.value.mergeInfo?.mergesWhenChecksPass).isFalse()
    }

    @Test
    fun a_branch_brought_up_to_date_is_told_and_read_again() = test {
        api.mergeInfo = api.mergeInfo.copy(behind = true)
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.updateBranch()
        assertThat(viewModel.state.value.isUpdating).isTrue()
        viewModel.updateBranch()
        advanceUntilIdle()

        assertThat(api.calls.filter { it.startsWith("updateBranch:") }).containsExactly("updateBranch:octo/tools#88")
        assertThat(viewModel.state.value.done?.first).isEqualTo(PullDone.UPDATED)
        assertThat(viewModel.state.value.mergeInfo?.behind).isFalse()
    }

    @Test
    fun an_update_the_forge_refuses_is_said_once_apart_from_merging() = test {
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()
        api.writeFailure = ForgeError.Http(422, "merge conflict between base and head")

        viewModel.updateBranch()
        advanceUntilIdle()

        assertThat(viewModel.state.value.updateError).isEqualTo(ForgeError.Http(422, "merge conflict between base and head"))
        assertThat(viewModel.state.value.mergeError).isNull()
        viewModel.updateErrorShown()
        assertThat(viewModel.state.value.updateError).isNull()
    }

    private val thread = ReviewThread(
        "T1", "src/Upload.kt", 12, onOldSide = false, hunk = null,
        comments = listOf(ThreadComment(1, ForgeUser("hubot", null, null), "Why three?", null)), resolved = false, canResolve = true,
    )

    @Test
    fun threads_are_read_with_the_pull_request_signed_out_too() = test {
        api.reviewThreads = listOf(thread)
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.threads).containsExactly(thread)
        assertThat(viewModel.state.value.resolvesThreads).isTrue()
        assertThat(viewModel.state.value.dismissesReviews).isTrue()
    }

    @Test
    fun a_reply_joins_its_thread_and_empties_its_field() = test {
        api.reviewThreads = listOf(thread)
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.reply(thread, "  Because of flaky links.  ")
        assertThat(viewModel.state.value.busyThread).isEqualTo("T1")
        // One thing at a time.
        viewModel.setResolved(thread, resolved = true)
        advanceUntilIdle()

        assertThat(api.calls.filter { it.startsWith("reply:") || it.startsWith("resolved:") }).containsExactly("reply:octo/tools#88:T1:Because of flaky links.")
        assertThat(viewModel.state.value.replied?.first).isEqualTo("T1")
        assertThat(viewModel.state.value.busyThread).isNull()
        // Read again: the reply is there.
        assertThat(viewModel.state.value.threads!!.single().comments.map { it.body }).containsExactly("Why three?", "Because of flaky links.").inOrder()
    }

    @Test
    fun the_reader_s_own_remark_is_rewritten_and_taken_back_and_nobody_else_s() = test {
        val mine = ThreadComment(2, ForgeUser("Me", null, null), "Because.", null)
        api.reviewThreads = listOf(thread.copy(comments = thread.comments + mine))
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.me).isEqualTo("me")
        assertThat(viewModel.state.value.editsThreadComments).isTrue()
        val read = viewModel.state.value.threads!!.single()

        viewModel.editThreadComment(read, mine, "  Because of flaky links.  ")
        advanceUntilIdle()
        assertThat(viewModel.state.value.threadEdited?.first).isEqualTo(2L)
        assertThat(viewModel.state.value.threads!!.single().comments.last().body).isEqualTo("Because of flaky links.")

        viewModel.deleteThreadComment(viewModel.state.value.threads!!.single(), mine)
        advanceUntilIdle()
        assertThat(viewModel.state.value.threads!!.single().comments.map { it.id }).containsExactly(1L)

        // Someone else's is left alone, whatever asks.
        viewModel.editThreadComment(read, read.comments.first(), "Not mine.")
        viewModel.deleteThreadComment(read, read.comments.first())
        advanceUntilIdle()
        assertThat(api.calls.filter { "ThreadComment:" in it }).containsExactly(
            "editThreadComment:octo/tools#88:T1:2:Because of flaky links.", "deleteThreadComment:octo/tools#88:T1:2",
        ).inOrder()
    }

    @Test
    fun a_reaction_on_a_remark_is_given_and_taken_back_in_place() = test {
        api.reviewThreads = listOf(thread)
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.reactsToThreadComments).isTrue()
        val remark = viewModel.state.value.threads!!.single().comments.single()

        viewModel.toggleThreadReaction(viewModel.state.value.threads!!.single(), remark, Reaction.HEART)
        advanceUntilIdle()
        assertThat(viewModel.state.value.threads!!.single().comments.single().reactions).containsExactly(Reaction.HEART, 1)

        viewModel.toggleThreadReaction(viewModel.state.value.threads!!.single(), remark, Reaction.HEART)
        advanceUntilIdle()
        assertThat(viewModel.state.value.threads!!.single().comments.single().reactions).isEmpty()
        assertThat(api.calls.filter { it.startsWith("threadReaction:") }).containsExactly(
            "threadReaction:octo/tools#88:1:HEART:me", "threadReaction:octo/tools#88:1:HEART:me",
        )
    }

    @Test
    fun a_thread_is_resolved_and_reopened() = test {
        api.reviewThreads = listOf(thread)
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setResolved(thread, resolved = true)
        advanceUntilIdle()
        assertThat(viewModel.state.value.threads!!.single().resolved).isTrue()
        viewModel.setResolved(thread.copy(resolved = true), resolved = false)
        advanceUntilIdle()

        assertThat(api.calls.filter { it.startsWith("resolved:") }).containsExactly("resolved:octo/tools#88:T1:true", "resolved:octo/tools#88:T1:false").inOrder()
        assertThat(viewModel.state.value.threads!!.single().resolved).isFalse()
    }

    @Test
    fun a_suggestion_is_committed_in_english_told_and_its_thread_read_again() = test {
        api.reviewThreads = listOf(thread)
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.appliesSuggestions).isTrue()

        viewModel.applySuggestion(thread, listOf("val retries = 3", "val delay = 2"))
        assertThat(viewModel.state.value.busyThread).isEqualTo("T1")
        advanceUntilIdle()

        assertThat(api.calls).contains("suggestion:octo/tools#88:T1:val retries = 3|val delay = 2:Apply suggestion from code review")
        assertThat(viewModel.state.value.done?.first).isEqualTo(PullDone.SUGGESTION_COMMITTED)
        assertThat(viewModel.state.value.busyThread).isNull()
        // Read again: the code it was on has moved on.
        assertThat(viewModel.state.value.threads!!.single().outdated).isTrue()
    }

    @Test
    fun a_suggestion_on_lines_that_changed_since_is_refused_and_said() = test {
        api.reviewThreads = listOf(thread)
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()

        api.writeFailure = ForgeError.Conflict
        viewModel.applySuggestion(thread, listOf("x"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.threadError).isEqualTo(ForgeError.Conflict)
        assertThat(viewModel.state.value.done).isNull()
        assertThat(viewModel.state.value.threads!!.single().outdated).isFalse()
    }

    @Test
    fun a_forge_that_cannot_commit_suggestions_says_so() = test {
        api.appliesSuggestions = false

        assertThat(viewModel().state.value.appliesSuggestions).isFalse()
    }

    @Test
    fun a_dismissed_review_has_the_conversation_read_again_and_a_refusal_is_said() = test {
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.dismissReview(80, "Outdated. ")
        advanceUntilIdle()
        assertThat(api.calls).contains("dismiss:octo/tools#88:80:Outdated.")
        assertThat(viewModel.state.value.done?.first).isEqualTo(PullDone.DISMISSED)

        api.writeFailure = ForgeError.Http(403, "no")
        viewModel.reply(thread, "Hm")
        advanceUntilIdle()
        assertThat(viewModel.state.value.threadError).isEqualTo(ForgeError.Http(403, "no"))
        viewModel.threadErrorShown()
        assertThat(viewModel.state.value.threadError).isNull()
    }

    @Test
    fun a_merge_the_forge_refuses_says_why_and_can_be_tried_again() = test {
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()
        api.writeFailure = ForgeError.Http(405, "Pull Request is not mergeable")

        viewModel.merge(MergeChoice(MergeMethod.MERGE))
        advanceUntilIdle()

        assertThat(viewModel.state.value.mergeError).isEqualTo(ForgeError.Http(405, "Pull Request is not mergeable"))
        assertThat(viewModel.state.value.done).isNull()
        viewModel.mergeErrorShown()
        assertThat(viewModel.state.value.mergeError).isNull()
    }

    @Test
    fun a_review_from_the_conversation_goes_without_remarks_on_lines() = test {
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.submitReview(ReviewVerdict.APPROVE, " Thanks! ")
        advanceUntilIdle()
        viewModel.submitReview(ReviewVerdict.APPROVE, "")
        advanceUntilIdle()

        assertThat(api.reviews.first()).isEqualTo(FakePullRequestApi.SentReview(ReviewVerdict.APPROVE, "Thanks!", emptyList()))
        // Done twice is told twice: the conversation is read again each time.
        assertThat(viewModel.state.value.done).isEqualTo(PullDone.REVIEWED to 2)
    }

    @Test
    fun a_review_the_forge_refuses_says_why() = test {
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()
        api.writeFailure = ForgeError.Http(422, "Can not approve your own pull request")

        viewModel.submitReview(ReviewVerdict.APPROVE, "")
        advanceUntilIdle()

        assertThat(viewModel.state.value.reviewError).isEqualTo(ForgeError.Http(422, "Can not approve your own pull request"))
        assertThat(viewModel.state.value.isSending).isFalse()
    }
}
