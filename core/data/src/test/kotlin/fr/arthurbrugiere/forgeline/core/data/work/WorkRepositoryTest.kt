package fr.arthurbrugiere.forgeline.core.data.work

import kotlinx.coroutines.launch
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeSearchApi
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WorkRepositoryTest {
    private val gitHub = FakeSearchApi()
    private val codeberg = FakeSearchApi()
    private val clients = FakeForgeClients(search = gitHub).also { it.put(ForgeInstance.Codeberg, FakeForgeClients(search = codeberg)) }
    private val accounts = FakeAccountRepository()
    private val repository = WorkRepository(clients, accounts)

    private fun found(repo: String, number: Int, forge: ForgeInstance = ForgeInstance.GitHub, pull: Boolean = true): IssueSearchResult {
        val (owner, name) = repo.split('/')
        return IssueSearchResult(RepoId(owner, name, forge), issueSummary(number, "Conversation $number", isPullRequest = pull))
    }

    private suspend fun signInToGitHub() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t-github")

    private suspend fun signInToCodeberg() = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("alice", null, null), "t-codeberg")

    private suspend fun load() = (repository.load() as ForgeResult.Success).value

    @Test
    fun signed_out_there_is_no_work_and_no_forge_is_asked() = runTest {
        val work = load()

        assertThat(work.isEmpty).isTrue()
        assertThat(work.forges).isEmpty()
        assertThat(gitHub.calls).isEmpty()
    }

    @Test
    fun each_kind_is_asked_of_the_account_s_forge_with_its_token_and_login() = runTest {
        signInToGitHub()
        gitHub.work[WorkKind.REVIEW_REQUESTED] = listOf(found("octo/tools", 4))
        gitHub.work[WorkKind.ASSIGNED] = listOf(found("octo/tools", 9, pull = false))

        val work = load()

        assertThat(work.sections[WorkKind.REVIEW_REQUESTED]).containsExactly(found("octo/tools", 4))
        assertThat(work.sections[WorkKind.OWN_PULL_REQUESTS]).isEmpty()
        assertThat(work.sections[WorkKind.ASSIGNED]).containsExactly(found("octo/tools", 9, pull = false))
        assertThat(work.isEmpty).isFalse()
        assertThat(work.forges).containsExactly(ForgeInstance.GitHub)
        assertThat(gitHub.calls).containsExactly("work:REVIEW_REQUESTED:octocat", "work:OWN_PULL_REQUESTS:octocat", "work:ASSIGNED:octocat")
        assertThat(gitHub.tokens.toSet()).containsExactly("t-github")
        assertThat(codeberg.calls).isEmpty()
    }

    @Test
    fun every_account_s_work_is_read_each_with_its_own_token_and_interleaved() = runTest {
        signInToGitHub()
        signInToCodeberg()
        gitHub.work[WorkKind.OWN_PULL_REQUESTS] = listOf(found("octo/a", 1), found("octo/a", 2), found("octo/a", 3))
        codeberg.work[WorkKind.OWN_PULL_REQUESTS] = listOf(found("alice/b", 7, ForgeInstance.Codeberg))

        val work = load()

        assertThat(work.sections[WorkKind.OWN_PULL_REQUESTS]!!.map { "${it.repo.fullName}#${it.issue.number}" })
            .containsExactly("octo/a#1", "alice/b#7", "octo/a#2", "octo/a#3").inOrder()
        assertThat(work.forges).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg)
        assertThat(work.failed).isEmpty()
        assertThat(codeberg.calls).contains("work:OWN_PULL_REQUESTS:alice")
        assertThat(codeberg.tokens.toSet()).containsExactly("t-codeberg")
    }

    @Test
    fun a_forge_that_fails_is_named_and_the_others_work_stays() = runTest {
        signInToGitHub()
        signInToCodeberg()
        gitHub.work[WorkKind.ASSIGNED] = listOf(found("octo/a", 1, pull = false))
        codeberg.failure = ForgeError.Network

        val work = load()

        assertThat(work.sections[WorkKind.ASSIGNED]).containsExactly(found("octo/a", 1, pull = false))
        assertThat(work.failed).containsExactly(ForgeInstance.Codeberg)
    }

    @Test
    fun a_forge_failing_for_one_kind_only_is_still_named() = runTest {
        signInToGitHub()
        gitHub.work[WorkKind.ASSIGNED] = listOf(found("octo/a", 1, pull = false))
        gitHub.workFailures[WorkKind.REVIEW_REQUESTED] = ForgeError.Http(500, "boom")

        val work = load()

        assertThat(work.sections[WorkKind.ASSIGNED]).hasSize(1)
        assertThat(work.failed).containsExactly(ForgeInstance.GitHub)
    }

    @Test
    fun every_forge_failing_is_a_failure() = runTest {
        signInToGitHub()
        signInToCodeberg()
        gitHub.failure = ForgeError.Network
        codeberg.failure = ForgeError.Network

        assertThat(repository.load()).isEqualTo(ForgeResult.Failure(ForgeError.Network))
    }

    @Test
    fun a_slow_forge_does_not_hold_back_the_others_work() = runTest {
        signInToGitHub()
        signInToCodeberg()
        gitHub.work[WorkKind.ASSIGNED] = listOf(found("octo/a", 1, pull = false))
        codeberg.work[WorkKind.ASSIGNED] = listOf(found("alice/b", 7, ForgeInstance.Codeberg, pull = false))
        codeberg.workGate = kotlinx.coroutines.CompletableDeferred()
        val seen = mutableListOf<Work>()

        val reading = launch { repository.stream().collect { seen += (it as ForgeResult.Success).value } }
        testScheduler.runCurrent()

        // GitHub's is there, and Codeberg is said to be on its way rather than to have nothing.
        assertThat(seen.single().sections[WorkKind.ASSIGNED]).containsExactly(found("octo/a", 1, pull = false))
        assertThat(seen.single().pending).containsExactly(ForgeInstance.Codeberg)
        assertThat(seen.single().forges).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg)

        codeberg.workGate!!.complete(Unit)
        reading.join()

        assertThat(seen).hasSize(2)
        assertThat(seen.last().pending).isEmpty()
        assertThat(seen.last().sections[WorkKind.ASSIGNED]!!.map { it.repo.fullName }).containsExactly("octo/a", "alice/b").inOrder()
    }

    @Test
    fun the_order_is_the_accounts_whichever_forge_answers_first() = runTest {
        signInToGitHub()
        signInToCodeberg()
        gitHub.work[WorkKind.ASSIGNED] = listOf(found("octo/a", 1, pull = false))
        codeberg.work[WorkKind.ASSIGNED] = listOf(found("alice/b", 7, ForgeInstance.Codeberg, pull = false))
        // This time GitHub is the slow one.
        gitHub.workGate = kotlinx.coroutines.CompletableDeferred()
        val seen = mutableListOf<Work>()

        val reading = launch { repository.stream().collect { seen += (it as ForgeResult.Success).value } }
        testScheduler.runCurrent()
        assertThat(seen.single().pending).containsExactly(ForgeInstance.GitHub)
        gitHub.workGate!!.complete(Unit)
        reading.join()

        assertThat(seen.last().sections[WorkKind.ASSIGNED]!!.map { it.repo.fullName }).containsExactly("octo/a", "alice/b").inOrder()
    }

    @Test
    fun a_forge_that_failed_first_is_not_called_a_failure_while_another_may_still_answer() = runTest {
        signInToGitHub()
        signInToCodeberg()
        gitHub.failure = ForgeError.Network
        codeberg.work[WorkKind.ASSIGNED] = listOf(found("alice/b", 7, ForgeInstance.Codeberg, pull = false))
        codeberg.workGate = kotlinx.coroutines.CompletableDeferred()
        val seen = mutableListOf<ForgeResult<Work>>()

        val reading = launch { repository.stream().collect { seen += it } }
        testScheduler.runCurrent()
        assertThat(seen).isEmpty()
        codeberg.workGate!!.complete(Unit)
        reading.join()

        val work = (seen.single() as ForgeResult.Success).value
        assertThat(work.failed).containsExactly(ForgeInstance.GitHub)
        assertThat(work.sections[WorkKind.ASSIGNED]).hasSize(1)
    }
}
