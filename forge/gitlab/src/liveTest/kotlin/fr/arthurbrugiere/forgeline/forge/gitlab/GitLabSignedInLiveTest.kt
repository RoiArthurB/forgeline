package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Forgeline's GitLab client against the real gitlab.com, signed in. It only ever reads, and is skipped without
 * GITLAB_LIVE_TOKEN. What it prints names no private data beyond counts.
 */
class GitLabSignedInLiveTest {
    private val token = System.getenv("GITLAB_LIVE_TOKEN").orEmpty()
    private val http = gitlabHttpClient(OkHttp.create())

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun the_signed_in_person_s_work_reads() = runBlocking {
        assumeTrue("GITLAB_LIVE_TOKEN not set", token.isNotBlank())
        val me = GitLabAuthApi(http).fetchAuthenticatedUser(token).value()
        val search = GitLabSearchApi(http)

        for (kind in WorkKind.entries) {
            val started = System.currentTimeMillis()
            val found = search.work(token, me.login, kind)
            assertWithMessage("$kind: ${(found as? ForgeResult.Failure)?.error}").that(found).isInstanceOf(ForgeResult.Success::class.java)
            System.err.println("GITLAB work $kind: ${found.value().size} in ${System.currentTimeMillis() - started} ms")
            // Whatever shows is open, on gitlab.com, in a project its address named.
            assertWithMessage("$kind open").that(found.value().all { it.issue.state == IssueState.OPEN }).isTrue()
            assertWithMessage("$kind forge").that(found.value().all { it.repo.forge == ForgeInstance.GitLab && it.repo.name.isNotBlank() }).isTrue()
            if (kind != WorkKind.ASSIGNED) assertWithMessage("$kind merge requests").that(found.value().all { it.issue.isPullRequest }).isTrue()
        }
    }
}
