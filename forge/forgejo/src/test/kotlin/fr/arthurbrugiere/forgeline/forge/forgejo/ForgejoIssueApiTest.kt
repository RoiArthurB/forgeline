package fr.arthurbrugiere.forgeline.forge.forgejo

import io.ktor.http.HttpStatusCode
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ForgejoIssueApiTest {
    private val codeberg = Codeberg()
    private val pull = IssueRef(RepoId("forgejo", "forgejo", ForgeInstance.Codeberg), 14597)

    private val api = with(codeberg) {
        ForgejoIssueApi(
            client {
                val path = it.url.encodedPath
                when {
                    path.endsWith("/reactions") -> json("null")
                    path.endsWith("/reviews") -> json(fixture("reviews.json"))
                    path.endsWith("/timeline") -> json(fixture("timeline.json"))
                    path.contains("/pulls/") -> json(fixture("pull.json"))
                    else -> json(fixture("issue_pull.json"))
                }
            },
            ForgeInstance.Codeberg,
        )
    }

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun a_merged_pull_request_carries_its_branches_and_size() = runTest {
        val issue = api.issue(null, pull).value()

        assertThat(issue.state).isEqualTo(IssueState.MERGED)
        assertThat(issue.reactions).isEmpty()
        val info = issue.pullRequest!!
        assertThat(info.isMerged).isTrue()
        assertThat(info.baseRef).isEqualTo("v17.0/forgejo")
        assertThat(info.additions).isEqualTo(54)
        assertThat(info.deletions).isEqualTo(13)
        assertThat(info.changedFiles).isEqualTo(5)
    }

    @Test
    fun the_timeline_keeps_the_conversation_and_drops_the_noise() = runTest {
        val items = api.timeline(null, pull, page = 1).value().items

        // Review requests, pushes, milestones, branch deletions and commit references are left out.
        assertThat(items.map { it::class.simpleName }).containsExactly("Labeled", "Review", "Comment", "StateChanged").inOrder()
        assertThat((items[0] as TimelineItem.Labeled).added).isTrue()
        assertThat((items[0] as TimelineItem.Labeled).label.name).isEqualTo("test/present")
        // The review entry doesn't say it approved: the pull request's reviews do.
        assertThat((items[1] as TimelineItem.Review).state).isEqualTo(ReviewState.APPROVED)
        assertThat((items[2] as TimelineItem.Comment).author?.login).isEqualTo("forgejo-actions")
        assertThat((items[3] as TimelineItem.StateChanged).change).isEqualTo(StateChange.MERGED)
    }

    @Test
    fun a_plain_issue_skips_the_pull_request_call() = runTest {
        val first = with(codeberg) { fixture("issues.json") }.let { all -> kotlinx.serialization.json.Json.parseToJsonElement(all) }
            .let { (it as kotlinx.serialization.json.JsonArray)[0].toString() }
        val issues = with(codeberg) {
            ForgejoIssueApi(client { if (it.url.encodedPath.endsWith("/reactions")) json("[]") else json(first) }, ForgeInstance.Codeberg)
        }

        val issue = issues.issue(null, IssueRef(pull.repo, 14601)).value()

        assertThat(issue.pullRequest).isNull()
        assertThat(issue.state).isEqualTo(IssueState.OPEN)
        assertThat(codeberg.requests.none { "/pulls/" in it.url.encodedPath }).isTrue()
    }

    @Test
    fun a_missing_issue_is_an_error() = runTest {
        val issues = with(codeberg) { ForgejoIssueApi(client { status(HttpStatusCode.NotFound) }, ForgeInstance.Codeberg) }

        val result = issues.issue(null, IssueRef(pull.repo, 1))

        assertThat((result as ForgeResult.Failure).error).isInstanceOf(ForgeError.Http::class.java)
    }
}
