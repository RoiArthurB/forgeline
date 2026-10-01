package fr.arthurbrugiere.forgeline.forge.forgejo

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.FeedPage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** activities.json holds one real entry of each kind from earl-warren's Codeberg activity (captured 2026-09-29). */
class ForgejoFeedApiTest {
    private val codeberg = Codeberg()
    private val following = """[{"login":"alice"},{"login":"bob"}]"""

    private val api = with(codeberg) {
        ForgejoFeedApi(
            client { request ->
                val path = request.url.encodedPath
                when {
                    path == "/api/v1/user/following" -> json(following)
                    // Followed people's activity: the same entries seen again under the same actions.
                    request.url.parameters["only-performed-by"] == "true" -> json(fixture("activities.json"))
                    else -> json(fixture("activities.json"), headers = mapOf("X-Total-Count" to "38151"))
                }
            },
            ForgeInstance.Codeberg,
        )
    }

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun each_kind_of_activity_becomes_its_feed_row() = runTest {
        val page: FeedPage = api.receivedEvents(null, "earl-warren", page = 1).value()
        val actions = page.events!!.map { it.action }

        assertThat(actions).contains(FeedAction.Issue(IssueAction.CLOSED, 191, ""))
        assertThat(actions).contains(FeedAction.Commented(191, null, isPullRequest = false))
        assertThat(actions).contains(FeedAction.Pushed("wip-nlnet"))
        assertThat(actions).contains(FeedAction.Reviewed(10632, ReviewState.APPROVED))
        assertThat(actions).contains(
            FeedAction.Issue(IssueAction.OPENED, 10639, "bug: end to end tests triggered by the build-release workflow cancel each other"),
        )
        // Unlike GitHub's, Forgejo's pull request activity carries the title.
        assertThat(actions).contains(
            FeedAction.PullRequest(PullRequestAction.OPENED, 10638, "chore(release-notes): teach release-notes-assistant that v11.0 is LTS [skip ci]"),
        )
        assertThat(page.events!!.all { it.repo.forge == ForgeInstance.Codeberg }).isTrue()
        assertThat(page.nextPage).isEqualTo(2)
    }

    @Test
    fun followed_people_join_the_first_page_and_the_same_action_shows_once() = runTest {
        val alone = api.receivedEvents(null, "earl-warren", page = 1).value().events!!
        val signedIn = api.receivedEvents("t", "earl-warren", page = 1).value().events!!

        // Read from two followed feeds and the own feed, each action still appears once.
        assertThat(signedIn).hasSize(alone.size)
        assertThat(codeberg.requests.count { it.url.parameters["only-performed-by"] == "true" }).isEqualTo(2)
        assertThat(signedIn.map { it.createdAt }).isInOrder(compareByDescending<java.time.Instant> { it })
    }

    @Test
    fun older_pages_only_follow_the_own_feed() = runTest {
        api.receivedEvents("t", "earl-warren", page = 2)

        assertThat(codeberg.requests.map { it.url.encodedPath }).containsExactly("/api/v1/users/earl-warren/activities/feeds")
        assertThat(codeberg.requests.single().url.parameters["page"]).isEqualTo("2")
    }

    @Test
    fun your_own_feed_and_the_people_you_follow_are_asked_together() = runTest {
        // Regression: the people you follow waited for your own feed, a round trip each to a far forge.
        val arrived = java.util.concurrent.atomic.AtomicInteger()
        val both = CompletableDeferred<Unit>()
        suspend fun meet() {
            if (arrived.incrementAndGet() == 2) both.complete(Unit)
            withTimeout(5_000) { both.await() }
        }
        val together = with(codeberg) {
            ForgejoFeedApi(
                client { request ->
                    val path = request.url.encodedPath
                    when {
                        path == "/api/v1/user/following" -> { meet(); json(following) }
                        request.url.parameters["only-performed-by"] == "true" -> json("[]")
                        else -> { meet(); json(fixture("activities.json")) }
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        assertThat(together.receivedEvents("t", "earl-warren", page = 1)).isInstanceOf(ForgeResult.Success::class.java)
    }

    @Test
    fun every_followed_person_read_in_a_refresh_is_asked_in_one_wave() = runTest {
        // Regression: 20 people were read 10 at a time. Codeberg takes about 3 s per feed from far away, so the second
        // wave doubled the wait.
        val people = (1..20).joinToString(",", "[", "]") { """{"login":"person$it"}""" }
        val arrived = java.util.concurrent.atomic.AtomicInteger()
        val all = CompletableDeferred<Unit>()
        val oneWave = with(codeberg) {
            ForgejoFeedApi(
                client { request ->
                    when {
                        request.url.encodedPath == "/api/v1/user/following" -> json(people)
                        request.url.parameters["only-performed-by"] == "true" -> {
                            if (arrived.incrementAndGet() == 20) all.complete(Unit)
                            withTimeout(5_000) { all.await() }
                            json("[]")
                        }
                        else -> json(fixture("activities.json"))
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        assertThat(oneWave.receivedEvents("t", "earl-warren", page = 1)).isInstanceOf(ForgeResult.Success::class.java)
    }
}
