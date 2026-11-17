package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/** Searching Tangled's index, and the Feed from its timeline; answers captured 2026-11-17 (`src/test/resources/tangled`). */
class TangledSearchTest {
    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    private fun didDocument(handle: String): (HttpRequestData) -> String? = {
        """{"id":"x","alsoKnownAs":["at://$handle"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://tngl.sh"}]}"""
    }

    private fun fake(answers: Map<String, (HttpRequestData) -> String?>) = FakeTangled(
        mapOf(
            "com.atproto.identity.resolveHandle" to resolved,
            "plc.directory/$OWNER_DID" to didDocument("tangled.org"),
            "plc.directory/$BOLTLESS" to didDocument("boltless.me"),
        ) + answers,
    )

    private fun search(fake: FakeTangled): TangledSearchApi {
        val identity = TangledIdentity(fake.client, fake.xrpc)
        return TangledSearchApi(fake.xrpc, identity, TangledRepoApi(fake.xrpc, identity).conversations)
    }

    @Test
    fun issues_and_pull_requests_are_found_among_tickets_and_named_by_their_repository() = runTest {
        val fake = fake(
            mapOf(
                "org.tangled.search.query" to { fixture("search_tickets.json") },
                // The second hit's repository can't be named: it's left out rather than shown nowhere.
                "org.tangled.repo.getRepoByRepoDid" to { r -> if (r.url.parameters["repoDid"] == REPO_DID) fixture("getRepoByRepoDid.json") else null },
            ),
        )

        val page = search(fake).issues(null, "editorconfig", 1).value()

        assertThat(fake.asked.single { it.startsWith("org.tangled.search.query") }).contains("nsid=org.tangled.track.ticket")
        assertThat(page.totalCount).isEqualTo(13)
        val hit = page.items.single()
        assertThat(hit.repo).isEqualTo(RepoId("tangled.org", "core", ForgeInstance.Tangled))
        assertThat(hit.issue.number).isEqualTo(3085)
        assertThat(hit.issue.title).isEqualTo("editorconfig: add editorconfig")
        // A ticket with a target branch is a pull request, and a completed one was merged.
        assertThat(hit.issue.isPullRequest).isTrue()
        assertThat(hit.issue.state).isEqualTo(IssueState.MERGED)
        assertThat(hit.issue.comments).isEqualTo(1)
    }

    @Test
    fun people_are_found_by_the_start_of_their_handle() = runTest {
        val fake = fake(mapOf("org.tangled.actor.searchActorsTypeahead" to { fixture("searchActorsTypeahead.json") }))

        val page = search(fake).users(null, "@oppi", 1).value()

        assertThat(page.items.map { it.login }).containsExactly("oppi.li", "oppili.tngl.sh", "appinn.bsky.social").inOrder()
        assertThat(page.items.all { it.forge == ForgeInstance.Tangled }).isTrue()
        assertThat(page.nextPage).isNull()
        assertThat(fake.asked.first { it.startsWith("org.tangled.actor") }).isEqualTo("org.tangled.actor.searchActorsTypeahead?limit=30&q=oppi")
    }

    @Test
    fun repositories_are_found_by_their_manifests() = runTest {
        val fake = fake(
            mapOf(
                "org.tangled.search.query" to {
                    """{"hits":[{"uri":"at://$REPO_DID/org.tangled.repo.manifest/self","nsid":"org.tangled.repo.manifest","score":3.2,
                        "value":${fixture("getRepoByRepoDid.json").substringAfter("\"value\":").removeSuffix("}")}}],"total":1}"""
                },
            ),
        )

        val page = search(fake).repositories(null, "monorepo", 1).value()

        assertThat(fake.asked.single { it.startsWith("org.tangled.search.query") }).contains("nsid=org.tangled.repo.manifest")
        val repo = page.items.single()
        assertThat(repo.id).isEqualTo(RepoId("tangled.org", "core", ForgeInstance.Tangled))
        assertThat(repo.description).isEqualTo("Monorepo for Tangled")
        assertThat(repo.starsKnown).isFalse()
    }

    @Test
    fun nothing_to_find_asks_nothing() = runTest {
        val fake = fake(emptyMap())

        assertThat(search(fake).issues(null, "  ", 1).value().items).isEmpty()
        assertThat(search(fake).users(null, "", 1).value().items).isEmpty()
        assertThat(fake.requests).isEmpty()
    }

    @Test
    fun the_feed_is_the_timeline_of_those_followed_stars_and_repositories_made() = runTest {
        val forked = """{"event":{"${'$'}type":"org.tangled.feed.getTimeline#repoEvent","uri":"at://did:plc:fork/org.tangled.repo.manifest/self","cid":"bafy",
            "repo":{"did":"did:plc:fork","slug":"core","createdAt":"2026-11-17T12:00:00Z","owner":{"did":"$BOLTLESS","handle":"boltless.me"}},
            "source":{"did":"$REPO_DID","slug":"core","createdAt":"2025-02-23T18:43:18Z","owner":{"did":"$OWNER_DID","handle":"tangled.org"}}},"eventAt":"2026-11-17T12:00:00Z"}"""
        val timeline = fixture("getTimeline.json").replaceFirst("""{"feed":[""", """{"feed":[$forked,""")
        val fake = fake(mapOf("org.tangled.feed.getTimeline" to { timeline }))
        val feed = TangledFeedApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc), TangledRepoApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).conversations)

        val page = feed.receivedEvents(null, "tangled.org", 1).value()

        val asked = fake.to("org.tangled.feed.getTimeline").single()
        assertThat(asked.url.parameters["viewer"]).isEqualTo(OWNER_DID)
        assertThat(asked.url.parameters["followingOnly"]).isEqualTo("true")
        // Follows have no row in the Feed.
        val events = page.events.orEmpty()
        assertThat(events.map { it.action }).containsExactly(FeedAction.Forked(RepoId("boltless.me", "core", ForgeInstance.Tangled)), FeedAction.Starred).inOrder()
        assertThat(events.first().repo).isEqualTo(RepoId("tangled.org", "core", ForgeInstance.Tangled))
        val star = events.last()
        assertThat(star.actor.login).isEqualTo("tjennerjahn.tngl.sh")
        assertThat(star.repo).isEqualTo(RepoId("nvim-mini.org", "mini.nvim", ForgeInstance.Tangled))
        assertThat(star.createdAt).isEqualTo(Instant.parse("2026-11-17T13:05:21.182Z"))
        assertThat(page.nextPage).isEqualTo(2)
    }
}
