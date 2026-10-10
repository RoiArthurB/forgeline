package fr.arthurbrugiere.forgeline.core.data.pull

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakePullRequestApi
import fr.arthurbrugiere.forgeline.core.testing.changedFile
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultPullRequestRepositoryTest {
    private val gitHub = FakePullRequestApi()
    private val gitLab = FakePullRequestApi(verdicts = setOf(ReviewVerdict.COMMENT, ReviewVerdict.APPROVE))
    private val codeberg = FakePullRequestApi(supportsBlame = false)
    private val clients = FakeForgeClients(pulls = gitHub).apply {
        put(ForgeInstance.GitLab, FakeForgeClients(pulls = gitLab))
        put(ForgeInstance.Codeberg, FakeForgeClients(pulls = codeberg))
    }
    private val accounts = FakeAccountRepository()
    private val repository = DefaultPullRequestRepository(clients, accounts)

    private val tools = RepoId("octo", "tools")
    private val pull = IssueRef(tools, 88, isPullRequest = true)
    private val merge = IssueRef(RepoId("group", "app", ForgeInstance.GitLab), 7, isPullRequest = true)

    @Test
    fun a_change_is_read_signed_out_from_its_own_forge() = runTest {
        gitHub.files = listOf(listOf(changedFile("a.kt")))
        gitLab.files = listOf(listOf(changedFile("b.go")), listOf(changedFile("c.go")))

        assertThat((repository.files(pull) as ForgeResult.Success).value.files.single().path).isEqualTo("a.kt")
        val second = (repository.files(merge, page = 2) as ForgeResult.Success).value
        assertThat(second.files.single().path).isEqualTo("c.go")
        assertThat(gitHub.calls).containsExactly("files:octo/tools#88:1")
        assertThat(gitLab.calls).containsExactly("files:group/app#7:2")
    }

    @Test
    fun what_changes_a_pull_request_needs_an_account_on_its_forge() = runTest {
        // Signed in on GitHub only: GitLab is asked nothing.
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")

        assertThat(repository.merge(merge, MergeMethod.MERGE)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(repository.review(merge, ReviewVerdict.APPROVE, "")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(repository.mergeInfo(merge)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(repository.create(merge.repo, NewPullRequest("T", "", "a", "b"))).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(gitLab.calls).isEmpty()

        assertThat(repository.merge(pull, MergeMethod.SQUASH)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(repository.review(pull, ReviewVerdict.APPROVE, "Good")).isEqualTo(ForgeResult.Success(Unit))
        assertThat(repository.create(tools, NewPullRequest("T", "", "a", "b"))).isEqualTo(ForgeResult.Success(IssueRef(tools, 100, isPullRequest = true)))
        assertThat(gitHub.calls).containsExactly("merge:octo/tools#88:SQUASH", "review:octo/tools#88:APPROVE", "create:octo/tools:a->b").inOrder()
    }

    @Test
    fun each_forge_says_what_a_review_can_decide_and_whether_it_blames() = runTest {
        assertThat(repository.verdicts(ForgeInstance.GitHub)).contains(ReviewVerdict.REQUEST_CHANGES)
        assertThat(repository.verdicts(ForgeInstance.GitLab)).doesNotContain(ReviewVerdict.REQUEST_CHANGES)
        assertThat(repository.supportsBlame(ForgeInstance.GitHub)).isTrue()
        assertThat(repository.supportsBlame(ForgeInstance.Codeberg)).isFalse()
    }

    @Test
    fun a_history_and_a_blame_name_their_ref_and_path() = runTest {
        repository.history(tools, ref = "main", path = "a.kt", page = 2)
        repository.history(tools, ref = null, path = null)
        repository.blame(tools, "main", "a.kt")
        repository.commit(tools, "abc")

        assertThat(gitHub.calls).containsExactly("history:octo/tools:main:a.kt:2", "history:octo/tools:::1", "blame:octo/tools:main:a.kt", "commit:octo/tools:abc").inOrder()
    }
}
