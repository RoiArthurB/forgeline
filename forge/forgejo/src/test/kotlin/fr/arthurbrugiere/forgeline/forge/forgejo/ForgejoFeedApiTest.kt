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

    private val since = java.time.Instant.parse("2026-09-01T00:00:00Z")

    private fun repo(owner: String, name: String, releases: Int? = 3, updatedAt: String = "2026-09-20T10:00:00Z", archived: Boolean = false) =
        """{"name":"$name","owner":{"login":"$owner","avatar_url":"https://avatars.example/$owner"},"updated_at":"$updatedAt","archived":$archived""" +
            (releases?.let { ""","release_counter":$it""" } ?: "") + "}"

    private fun release(tag: String, publishedAt: String, prerelease: Boolean = false, draft: Boolean = false, author: String? = "release-team") =
        """[{"tag_name":"$tag","name":"$tag","published_at":"$publishedAt","prerelease":$prerelease,"draft":$draft""" +
            (author?.let { ""","author":{"login":"$it"}""" } ?: "") + "}]"

    private fun starredApi(starred: String, releases: Map<String, String>) = with(codeberg) {
        ForgejoFeedApi(
            client { request ->
                val path = request.url.encodedPath
                when {
                    path == "/api/v1/user/starred" -> json(if (request.url.parameters["page"] == "1") starred else "[]")
                    path.endsWith("/releases") -> json(releases[path.removePrefix("/api/v1/repos/").removeSuffix("/releases")] ?: "[]")
                    else -> status(io.ktor.http.HttpStatusCode.NotFound)
                }
            },
            ForgeInstance.Codeberg,
        )
    }

    @Test
    fun starred_repositories_give_their_latest_release() = runTest {
        val api = starredApi(
            "[${repo("forgejo", "forgejo")},${repo("acme", "rocket")}]",
            mapOf(
                "forgejo/forgejo" to release("v16.0.5", "2026-09-17T17:27:32+02:00"),
                "acme/rocket" to release("v2.0.0-rc1", "2026-09-10T08:00:00Z", prerelease = true, author = null),
            ),
        )

        val events = api.starredActivity("t", since).value()

        assertThat(events.map { it.repo.key to it.action }).containsExactly(
            "codeberg.org/forgejo/forgejo" to FeedAction.Released("v16.0.5", "v16.0.5", prerelease = false),
            "codeberg.org/acme/rocket" to FeedAction.Released("v2.0.0-rc1", "v2.0.0-rc1", prerelease = true),
        )
        // Signed by its author, or by the repository's owner when automation made it.
        assertThat(events.map { it.actor.login }).containsExactly("release-team", "acme")
        assertThat(events.first().createdAt).isEqualTo(java.time.Instant.parse("2026-09-17T15:27:32Z"))
    }

    @Test
    fun only_starred_repositories_that_could_have_a_recent_release_are_asked() = runTest {
        val api = starredApi(
            "[" + listOf(
                repo("live", "one"),
                repo("no", "releases", releases = 0),
                repo("long", "asleep", updatedAt = "2025-01-01T00:00:00Z"),
                repo("shelved", "repo", archived = true),
                // An older server doesn't count releases: ask rather than miss one.
                repo("old", "server", releases = null),
            ).joinToString(",") + "]",
            mapOf("live/one" to release("v1", "2026-09-10T08:00:00Z"), "old/server" to release("v0.1", "2024-05-05T08:00:00Z")),
        )

        val events = api.starredActivity("t", since).value()

        assertThat(codeberg.requests.map { it.url.encodedPath }.filter { it.endsWith("/releases") })
            .containsExactly("/api/v1/repos/live/one/releases", "/api/v1/repos/old/server/releases")
        // old/server's latest release is from 2024: asked, but too old to show. Drafts never show either.
        assertThat(events.map { it.repo.fullName }).containsExactly("live/one")
    }

    @Test
    fun starred_releases_are_asked_all_at_once() = runTest {
        // Each release list is a round trip to a far forge: one after another, 40 stars would take minutes.
        val names = (1..12).map { "repo$it" }
        val arrived = java.util.concurrent.atomic.AtomicInteger()
        val all = CompletableDeferred<Unit>()
        val api = with(codeberg) {
            ForgejoFeedApi(
                client { request ->
                    when {
                        request.url.encodedPath == "/api/v1/user/starred" ->
                            json(if (request.url.parameters["page"] == "1") names.joinToString(",", "[", "]") { repo("o", it) } else "[]")
                        else -> {
                            if (arrived.incrementAndGet() == names.size) all.complete(Unit)
                            withTimeout(5_000) { all.await() }
                            json("[]")
                        }
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        assertThat(api.starredActivity("t", since)).isInstanceOf(ForgeResult.Success::class.java)
    }

    @Test
    fun starred_repositories_that_cannot_be_listed_are_a_failure() = runTest {
        val api = with(codeberg) { ForgejoFeedApi(client { status(io.ktor.http.HttpStatusCode.Unauthorized) }, ForgeInstance.Codeberg) }

        assertThat(api.starredActivity("t", since)).isInstanceOf(ForgeResult.Failure::class.java)
    }
}
