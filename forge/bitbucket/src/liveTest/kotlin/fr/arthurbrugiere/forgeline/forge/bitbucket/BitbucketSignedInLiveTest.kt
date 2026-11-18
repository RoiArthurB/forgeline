package fr.arthurbrugiere.forgeline.forge.bitbucket

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.SearchSort
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Forgeline's Bitbucket client signed in, with BITBUCKET_LIVE_EMAIL and BITBUCKET_LIVE_TOKEN (an Atlassian API token
 * with Bitbucket's read scopes): who one is, one's workspaces and their repositories, search, and one's work. It only
 * reads, and is skipped without them.
 */
class BitbucketSignedInLiveTest {
    private val email = System.getenv("BITBUCKET_LIVE_EMAIL").orEmpty()
    private val token = System.getenv("BITBUCKET_LIVE_TOKEN").orEmpty()
    private val credential get() = bitbucketBasicCredential(email, token)
    private val apis = BitbucketApis(bitbucketHttpClient(OkHttp.create()))

    private fun <T> ForgeResult<T>.value(what: String = ""): T = (this as? ForgeResult.Success)?.value ?: error("$what: $this")

    @Before
    fun signedIn() = assumeTrue("BITBUCKET_LIVE_EMAIL and BITBUCKET_LIVE_TOKEN unset", email.isNotBlank() && token.isNotBlank())

    @Test
    fun one_signs_in_and_reads_ones_workspaces_and_repositories() = runBlocking<Unit> {
        val me = apis.auth.signInWithPassword(email, token).value("sign in")
        val user = apis.auth.fetchAuthenticatedUser(me.accessToken).value("user")
        assertWithMessage("login").that(user.login).isNotEmpty()

        val reachable = apis.users.reachableRepos(credential).value("reachable")
        val organizations = apis.users.organizations(credential).value("organizations")
        // Every workspace one is in lists its repositories, and a repository reads with one's rights on it.
        reachable.firstOrNull()?.let { repo ->
            val rights = apis.issues.access(credential, repo.id).value("rights")
            assertWithMessage("rights on ${repo.id.fullName}").that(rights.access).isNotNull()
        }
        organizations.forEach { workspace ->
            apis.users.repos(credential, workspace.login, mine = false, organization = true).value("repositories of ${workspace.login}")
        }
    }

    @Test
    fun ones_repositories_are_found_by_name_and_ones_work_is_listed() = runBlocking<Unit> {
        val user = apis.auth.fetchAuthenticatedUser(credential).value("user")
        val some = apis.users.reachableRepos(credential).value("reachable").firstOrNull() ?: return@runBlocking
        val found = apis.search.repositories(credential, some.id.name, 1, SearchSort.UPDATED).value("search")
        assertWithMessage("found").that(found.items.map { it.id }).contains(some.id)
        WorkKind.entries.forEach { apis.search.work(credential, user.login, it).value("work $it") }
    }
}
