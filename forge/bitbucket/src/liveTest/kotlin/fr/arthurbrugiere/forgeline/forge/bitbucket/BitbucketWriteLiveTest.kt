package fr.arthurbrugiere.forgeline.forge.bitbucket

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Forgeline's Bitbucket writes on a scratch repository of one's own, BITBUCKET_LIVE_REPO (`workspace/name`), with
 * BITBUCKET_LIVE_WRITES=1 and a token with Bitbucket's write scopes. Each write is undone. No workflow runs it.
 */
class BitbucketWriteLiveTest {
    private val email = System.getenv("BITBUCKET_LIVE_EMAIL").orEmpty()
    private val token = System.getenv("BITBUCKET_LIVE_TOKEN").orEmpty()
    private val credential get() = bitbucketBasicCredential(email, token)
    private val repo = System.getenv("BITBUCKET_LIVE_REPO").orEmpty().split('/').takeIf { it.size == 2 }?.let { RepoId(it[0], it[1], ForgeInstance.Bitbucket) }
    private val apis = BitbucketApis(bitbucketHttpClient(OkHttp.create()))

    private fun <T> ForgeResult<T>.value(what: String = ""): T = (this as? ForgeResult.Success)?.value ?: error("$what: $this")

    @Before
    fun allowed() = assumeTrue(
        "BITBUCKET_LIVE_WRITES=1, BITBUCKET_LIVE_REPO, BITBUCKET_LIVE_EMAIL and BITBUCKET_LIVE_TOKEN needed",
        System.getenv("BITBUCKET_LIVE_WRITES") == "1" && repo != null && email.isNotBlank() && token.isNotBlank(),
    )

    @Test
    fun a_description_is_changed_and_put_back() = runBlocking<Unit> {
        val id = repo!!
        val before = apis.repoSettings.settings(credential, id).value("settings")
        val changed = apis.repoSettings.change(credential, id, RepoSettingsChange.Description("Forgeline live test")).value("change")
        assertWithMessage("description").that(changed.description).isEqualTo("Forgeline live test")
        apis.repoSettings.change(credential, id, RepoSettingsChange.Description(before.description)).value("put back")
    }

    @Test
    fun a_comment_on_the_first_open_pull_request_is_posted_rewritten_and_deleted() = runBlocking<Unit> {
        val id = repo!!
        val pull = apis.repos.pullRequests(credential, id).value("pull requests").firstOrNull()
        assumeTrue("no open pull request on $id", pull != null)
        val ref = IssueRef(id, pull!!.number, isPullRequest = true)

        val comment = apis.issues.comment(credential, ref, "Forgeline live test").value("comment")
        apis.issues.editComment(credential, ref, comment.id, "Forgeline live test, rewritten").value("edit")
        val said = apis.issues.timeline(credential, ref, 1).value("timeline").items.filterIsInstance<TimelineItem.Comment>().firstOrNull { it.id == comment.id }
        assertWithMessage("rewritten").that(said?.body).isEqualTo("Forgeline live test, rewritten")
        apis.issues.deleteComment(credential, ref, comment.id).value("delete")
    }
}
