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
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
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
        assertThat(api.calls).containsExactly("checks:octo/tools#88")
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

        viewModel.merge(MergeMethod.SQUASH)
        assertThat(viewModel.state.value.isMerging).isTrue()
        // A second tap while it is on its way sends nothing more.
        viewModel.merge(MergeMethod.SQUASH)
        advanceUntilIdle()

        assertThat(api.calls.filter { it.startsWith("merge:") }).containsExactly("merge:octo/tools#88:SQUASH")
        assertThat(viewModel.state.value.isMerging).isFalse()
        assertThat(viewModel.state.value.done?.first).isEqualTo(PullDone.MERGED)
    }

    @Test
    fun a_merge_the_forge_refuses_says_why_and_can_be_tried_again() = test {
        signIn()
        val viewModel = viewModel()
        advanceUntilIdle()
        api.writeFailure = ForgeError.Http(405, "Pull Request is not mergeable")

        viewModel.merge(MergeMethod.MERGE)
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
