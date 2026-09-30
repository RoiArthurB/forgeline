package fr.arthurbrugiere.forgeline.forge.forgejo

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
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
    fun a_plain_issue_has_no_pull_request_details() = runTest {
        val first = with(codeberg) { fixture("issues.json") }.let { all -> kotlinx.serialization.json.Json.parseToJsonElement(all) }
            .let { (it as kotlinx.serialization.json.JsonArray)[0].toString() }
        val issues = with(codeberg) {
            ForgejoIssueApi(client { if (it.url.encodedPath.endsWith("/reactions")) json("[]") else json(first) }, ForgeInstance.Codeberg)
        }

        val issue = issues.issue(null, IssueRef(pull.repo, 14601)).value()

        assertThat(issue.pullRequest).isNull()
        assertThat(issue.state).isEqualTo(IssueState.OPEN)
        // The pull request call went out alongside (it can't be known before): its 404 is ignored.
        assertThat(issue.pullRequest).isNull()
    }

    @Test
    fun a_missing_issue_is_an_error() = runTest {
        val issues = with(codeberg) { ForgejoIssueApi(client { status(HttpStatusCode.NotFound) }, ForgeInstance.Codeberg) }

        val result = issues.issue(null, IssueRef(pull.repo, 1))

        assertThat((result as ForgeResult.Failure).error).isInstanceOf(ForgeError.Http::class.java)
    }

    @Test
    fun a_pull_requests_details_are_asked_alongside_the_issue_not_after() = runTest {
        // Regression: the details waited for the issue to say it's a pull request, a round trip each to a far forge.
        val bothAsked = CompletableDeferred<Unit>()
        val asked = mutableSetOf<String>()
        val issues = with(codeberg) {
            ForgejoIssueApi(
                client {
                    val path = it.url.encodedPath
                    when {
                        path.endsWith("/reactions") -> json("null")
                        path.contains("/pulls/") -> {
                            asked += "pull"
                            if ("issue" in asked) bothAsked.complete(Unit)
                            withTimeout(5_000) { bothAsked.await() }
                            json(fixture("pull.json"))
                        }
                        else -> {
                            asked += "issue"
                            if ("pull" in asked) bothAsked.complete(Unit)
                            withTimeout(5_000) { bothAsked.await() }
                            json(fixture("issue_pull.json"))
                        }
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        assertThat(issues.issue(null, pull).value().pullRequest?.isMerged).isTrue()
    }

    @Test
    fun a_pull_requests_title_takes_one_request() = runTest {
        val issues = with(codeberg) { ForgejoIssueApi(client { json(fixture("issue_pull.json")) }, ForgeInstance.Codeberg) }

        assertThat(issues.title(null, pull).value()).isNotEmpty()
        assertThat(codeberg.requests.single().url.encodedPath).isEqualTo("/api/v1/repos/forgejo/forgejo/issues/14597")
    }

    @Test
    fun a_plain_issue_never_waits_for_the_pull_request_call_asked_alongside() = runTest {
        val first = with(codeberg) { fixture("issues.json") }.let { kotlinx.serialization.json.Json.parseToJsonElement(it) }
            .let { (it as kotlinx.serialization.json.JsonArray)[0].toString() }
        val never = CompletableDeferred<Unit>()
        val issues = with(codeberg) {
            ForgejoIssueApi(
                client {
                    val path = it.url.encodedPath
                    when {
                        // The pull request call hangs: a plain issue must not wait for it.
                        path.contains("/pulls/") -> { never.await(); json("{}") }
                        path.endsWith("/reactions") -> json("[]")
                        else -> json(first)
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        val issue = withTimeout(5_000) { issues.issue(null, IssueRef(pull.repo, 14601)) }

        assertThat(issue.value().pullRequest).isNull()
    }

    @Test
    fun review_states_are_asked_alongside_the_timeline_not_after() = runTest {
        // Regression: the reviews waited for the timeline to show a review, a round trip each to a far forge.
        val arrived = java.util.concurrent.atomic.AtomicInteger()
        val both = CompletableDeferred<Unit>()
        suspend fun meet() {
            if (arrived.incrementAndGet() == 2) both.complete(Unit)
            withTimeout(5_000) { both.await() }
        }
        val issues = with(codeberg) {
            ForgejoIssueApi(
                client {
                    val path = it.url.encodedPath
                    when {
                        path.endsWith("/reviews") -> { meet(); json(fixture("reviews.json")) }
                        else -> { meet(); json(fixture("timeline.json")) }
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        val items = issues.timeline(null, pull, page = 1).value().items

        assertThat(items.filterIsInstance<TimelineItem.Review>()).isNotEmpty()
    }

    @Test
    fun a_timeline_without_reviews_never_waits_for_them() = runTest {
        val never = CompletableDeferred<Unit>()
        val issues = with(codeberg) {
            ForgejoIssueApi(
                client { if (it.url.encodedPath.endsWith("/reviews")) { never.await(); json("[]") } else json("[]") },
                ForgeInstance.Codeberg,
            )
        }

        val page = withTimeout(5_000) { issues.timeline(null, pull, page = 1) }

        assertThat(page.value().items).isEmpty()
    }
}
