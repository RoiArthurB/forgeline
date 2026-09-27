package fr.arthurbrugiere.forgeline.core.data.issue

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultIssueRepositoryTest {
    private val api = FakeIssueApi()
    private val accounts = FakeAccountRepository()
    private val repository = DefaultIssueRepository(api, accounts)
    private val ref = IssueRef(RepoId("octo", "repo"), 7)

    @Test
    fun a_viewed_conversation_is_remembered_for_an_instant_reopen() = runTest {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi")), null)

        assertThat(repository.cached(ref)).isNull()
        repository.issue(ref)
        repository.timeline(ref, 1)

        val cached = repository.cached(ref)!!
        assertThat(cached.issue).isEqualTo(issueDetails(ref))
        assertThat(cached.firstPage?.items).containsExactly(comment(1, "Hi"))
    }

    @Test
    fun failures_are_returned_and_do_not_erase_the_cache() = runTest {
        api.issues[ref] = issueDetails(ref)
        repository.issue(ref)
        api.failure = ForgeError.Network

        assertThat(repository.issue(ref)).isEqualTo(ForgeResult.Failure(ForgeError.Network))
        assertThat(repository.cached(ref)?.issue).isEqualTo(issueDetails(ref))
    }

    @Test
    fun calls_are_anonymous_signed_out_and_authenticated_signed_in() = runTest {
        api.issues[ref] = issueDetails(ref)
        repository.issue(ref)
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "ghp_token")
        repository.issue(ref)

        assertThat(api.tokens).containsExactly(null, "ghp_token").inOrder()
    }

    @Test
    fun the_cache_is_bounded() = runTest {
        repeat(DefaultIssueRepository.CACHE_SIZE + 5) { number ->
            val each = IssueRef(ref.repo, number)
            api.issues[each] = issueDetails(each)
            repository.issue(each)
        }

        assertThat(repository.cached(IssueRef(ref.repo, 0))).isNull()
        assertThat(repository.cached(IssueRef(ref.repo, DefaultIssueRepository.CACHE_SIZE + 4))).isNotNull()
    }
}
