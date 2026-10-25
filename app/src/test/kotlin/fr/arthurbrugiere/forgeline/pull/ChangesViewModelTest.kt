package fr.arthurbrugiere.forgeline.pull

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.pull.DefaultPullRequestRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.DiffLineKind
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakePullRequestApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.changedFile
import fr.arthurbrugiere.forgeline.core.testing.commit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakePullRequestApi()
    private val accounts = FakeAccountRepository()
    private val repository = DefaultPullRequestRepository(FakeForgeClients(pulls = api), accounts)
    private val tools = RepoId("octo", "tools")
    private val pull = IssueRef(tools, 88, isPullRequest = true)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun viewModel(target: ChangesTarget = ChangesTarget.Pull(pull)) = ChangesViewModel(target, repository, accounts)

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")

    private val large = "@@ -1,400 +1,400 @@\n" + (1..400).joinToString("\n") { "+line $it" }

    @Test
    fun a_pull_request_s_files_are_read_into_hunks() = test {
        api.files = listOf(listOf(changedFile("a.kt"), changedFile("logo.png", patch = null)))

        val viewModel = viewModel()
        assertThat(viewModel.state.value.isLoading).isTrue()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.files!!.map { it.file.path }).containsExactly("a.kt", "logo.png").inOrder()
        assertThat(state.files!![0].hunks.single().lines.map { it.kind }).containsExactly(DiffLineKind.CONTEXT, DiffLineKind.REMOVED, DiffLineKind.ADDED).inOrder()
        // Nothing to read for a file that isn't text.
        assertThat(state.files!![1].hunks).isEmpty()
        assertThat(state.nextPage).isNull()
    }

    @Test
    fun a_long_change_starts_folded_and_any_file_can_be_folded_by_hand() = test {
        api.files = listOf(listOf(changedFile("a.kt"), changedFile("package-lock.json", patch = large)))
        val viewModel = viewModel()
        advanceUntilIdle()
        val (small, lock) = viewModel.state.value.files!!

        assertThat(viewModel.state.value.isExpanded(small)).isTrue()
        assertThat(viewModel.state.value.isExpanded(lock)).isFalse()

        viewModel.toggle("a.kt")
        viewModel.toggle("package-lock.json")
        assertThat(viewModel.state.value.isExpanded(small)).isFalse()
        assertThat(viewModel.state.value.isExpanded(lock)).isTrue()

        viewModel.toggle("a.kt")
        assertThat(viewModel.state.value.isExpanded(small)).isTrue()
    }

    @Test
    fun more_files_are_read_when_the_end_comes_in_sight() = test {
        api.files = listOf(listOf(changedFile("a.kt")), listOf(changedFile("b.kt")))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.nextPage).isEqualTo(2)

        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(viewModel.state.value.files!!.map { it.file.path }).containsExactly("a.kt", "b.kt").inOrder()
        assertThat(viewModel.state.value.nextPage).isNull()
        // Asked once, though the end was seen twice.
        assertThat(api.calls).containsExactly("files:octo/tools#88:1", "files:octo/tools#88:2").inOrder()
    }

    @Test
    fun a_change_that_cannot_be_read_says_so_and_can_be_asked_again() = test {
        api.failure = ForgeError.Network
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.files).isNull()

        api.failure = null
        api.files = listOf(listOf(changedFile("a.kt")))
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.files).hasSize(1)
    }

    @Test
    fun a_commit_s_change_comes_with_the_commit_and_is_not_reviewed() = test {
        signIn()
        api.commitDetails["abc"] = CommitDetails(commit("abc", "Fix the upload"), listOf(changedFile("a.kt")))

        val viewModel = viewModel(ChangesTarget.OfCommit(tools, "abc"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.commit?.title).isEqualTo("Fix the upload")
        assertThat(viewModel.state.value.files!!.single().file.path).isEqualTo("a.kt")
        assertThat(viewModel.state.value.canReview).isFalse()
    }

    @Test
    fun reviewing_takes_an_account_on_the_pull_request_s_forge() = test {
        api.files = listOf(listOf(changedFile("a.kt")))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.canReview).isFalse()
        // Signed out, a tapped line starts nothing.
        viewModel.startComment("a.kt", viewModel.state.value.files!![0].hunks[0].lines[2])
        assertThat(viewModel.state.value.drafting).isNull()

        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "tok")
        advanceUntilIdle()
        assertThat(viewModel.state.value.canReview).isFalse()

        signIn()
        advanceUntilIdle()
        assertThat(viewModel.state.value.canReview).isTrue()
    }

    @Test
    fun a_remark_covers_lines_above_it_on_its_side_and_another_the_whole_file() = test {
        signIn()
        api.files = listOf(listOf(changedFile("a.kt")))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.remarksOnRanges).isTrue()
        assertThat(viewModel.state.value.remarksOnFiles).isTrue()
        val lines = viewModel.state.value.files!![0].hunks[0].lines

        // On the line it adds, the lines above that the new file has: the removed one isn't, the one left alone is.
        viewModel.startComment("a.kt", lines[2])
        assertThat(viewModel.state.value.drafting?.above).containsExactly(lines[0])
        viewModel.addComment("Both of these", start = lines[0])
        viewModel.startFileComment("a.kt")
        assertThat(viewModel.state.value.drafting).isEqualTo(LineTarget("a.kt", null))
        viewModel.addComment("Split this file?")

        assertThat(viewModel.state.value.comments).containsExactly(
            LineComment("a.kt", oldLine = null, newLine = 2, body = "Both of these", startOldLine = 1, startNewLine = 1),
            LineComment("a.kt", oldLine = null, newLine = null, body = "Split this file?", onFile = true),
        ).inOrder()
    }

    @Test
    fun where_the_forge_takes_one_line_only_no_line_above_is_offered() = test {
        signIn()
        api.commentsOnRanges = false
        api.files = listOf(listOf(changedFile("a.kt")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startComment("a.kt", viewModel.state.value.files!![0].hunks[0].lines[2])

        assertThat(viewModel.state.value.drafting?.above).isEmpty()
    }

    @Test
    fun files_the_reader_viewed_start_folded_and_are_marked_and_unmarked() = test {
        signIn()
        api.files = listOf(listOf(changedFile("a.kt"), changedFile("b.kt")))
        api.viewed = mutableSetOf("a.kt")
        val viewModel = viewModel()
        advanceUntilIdle()
        val (a, b) = viewModel.state.value.files!!
        assertThat(viewModel.state.value.marksFilesViewed).isTrue()
        assertThat(viewModel.state.value.viewed).containsExactly("a.kt")
        assertThat(viewModel.state.value.isExpanded(a)).isFalse()
        assertThat(viewModel.state.value.isExpanded(b)).isTrue()

        viewModel.toggleViewed("b.kt")
        // Folded at once, as on GitHub, before the forge has answered.
        assertThat(viewModel.state.value.isExpanded(b)).isFalse()
        advanceUntilIdle()
        viewModel.toggleViewed("a.kt")
        advanceUntilIdle()

        assertThat(viewModel.state.value.viewed).containsExactly("b.kt")
        assertThat(viewModel.state.value.isExpanded(a)).isTrue()
        assertThat(api.calls.filter { it.startsWith("viewed:") }).containsExactly("viewed:octo/tools#88:b.kt:true", "viewed:octo/tools#88:a.kt:false").inOrder()
    }

    @Test
    fun a_file_the_forge_wouldn_t_mark_goes_back_as_it_was() = test {
        signIn()
        api.files = listOf(listOf(changedFile("a.kt")))
        val viewModel = viewModel()
        advanceUntilIdle()
        api.writeFailure = fr.arthurbrugiere.forgeline.core.forge.ForgeError.Network

        viewModel.toggleViewed("a.kt")
        advanceUntilIdle()

        assertThat(viewModel.state.value.viewed).isEmpty()
        assertThat(viewModel.state.value.error).isEqualTo(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Network)
    }

    @Test
    fun remarks_on_lines_wait_for_the_review_and_go_with_it() = test {
        signIn()
        api.files = listOf(listOf(changedFile("new/a.kt", change = FileChange.RENAMED, previousPath = "old/a.kt")))
        val viewModel = viewModel()
        advanceUntilIdle()
        val lines = viewModel.state.value.files!![0].hunks[0].lines

        viewModel.startComment("new/a.kt", lines[2])
        assertThat(viewModel.state.value.drafting?.line).isEqualTo(lines[2])
        viewModel.addComment("  Why twice?  ")
        viewModel.startComment("new/a.kt", lines[1])
        viewModel.addComment("This was needed")
        viewModel.startComment("new/a.kt", lines[0])
        viewModel.cancelComment()

        val added = LineComment("new/a.kt", oldLine = null, newLine = 2, body = "Why twice?", previousPath = "old/a.kt")
        val removed = LineComment("new/a.kt", oldLine = 2, newLine = null, body = "This was needed", previousPath = "old/a.kt")
        assertThat(viewModel.state.value.comments).containsExactly(added, removed).inOrder()
        assertThat(viewModel.state.value.drafting).isNull()
        // Nothing has left the phone yet.
        assertThat(api.reviews).isEmpty()

        viewModel.removeComment(removed)
        viewModel.openReview()
        viewModel.submitReview(ReviewVerdict.REQUEST_CHANGES, " One thing. ")
        assertThat(viewModel.state.value.isSending).isTrue()
        advanceUntilIdle()

        assertThat(api.reviews).containsExactly(FakePullRequestApi.SentReview(ReviewVerdict.REQUEST_CHANGES, "One thing.", listOf(added)))
        val state = viewModel.state.value
        assertThat(state.isReviewOpen).isFalse()
        assertThat(state.comments).isEmpty()
        assertThat(state.reviewSent).isTrue()
        viewModel.reviewSentShown()
        assertThat(viewModel.state.value.reviewSent).isFalse()
    }

    @Test
    fun a_review_the_forge_refuses_keeps_what_was_written() = test {
        signIn()
        api.files = listOf(listOf(changedFile("a.kt")))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startComment("a.kt", viewModel.state.value.files!![0].hunks[0].lines[2])
        viewModel.addComment("Hm")
        viewModel.openReview()

        api.writeFailure = ForgeError.Http(422, "Unprocessable")
        viewModel.submitReview(ReviewVerdict.APPROVE, "")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.reviewError).isEqualTo(ForgeError.Http(422, "Unprocessable"))
        assertThat(state.isReviewOpen).isTrue()
        assertThat(state.comments).hasSize(1)
        assertThat(state.reviewSent).isFalse()
    }

    @Test
    fun an_empty_remark_is_not_kept_and_each_forge_says_what_a_review_can_decide() = test {
        signIn()
        api.files = listOf(listOf(changedFile("a.kt")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startComment("a.kt", viewModel.state.value.files!![0].hunks[0].lines[0])
        viewModel.addComment("   ")

        assertThat(viewModel.state.value.comments).isEmpty()
        assertThat(viewModel.state.value.verdicts).containsExactlyElementsIn(ReviewVerdict.entries)
    }
}
