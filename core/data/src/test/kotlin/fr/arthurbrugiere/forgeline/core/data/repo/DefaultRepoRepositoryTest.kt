package fr.arthurbrugiere.forgeline.core.data.repo

import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.testing.Rendezvous
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoApi
import fr.arthurbrugiere.forgeline.core.testing.repoDetails
import kotlinx.coroutines.flow.first
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class DefaultRepoRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val api = FakeRepoApi()
    private val accounts = FakeAccountRepository()
    private var now = Instant.parse("2026-09-26T08:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val repository = DefaultRepoRepository(database.repoDao(), FakeForgeClients(repos = api), accounts, clock)

    private val id = RepoId("octo", "repo")
    private val readme = Readme("README.md", "# Hello")

    @After
    fun closeDatabase() = database.close()

    @Test
    fun nothing_is_known_before_the_first_refresh() = runTest {
        val snapshot = repository.observe(id).first()

        assertThat(snapshot.details).isNull()
        assertThat(snapshot.fetchedAtMillis).isNull()
    }

    @Test
    fun refresh_caches_details_and_readme() = runTest {
        api.details[id] = repoDetails("octo/repo")
        api.readmes[id] = readme

        assertThat(repository.refresh(id)).isEqualTo(RefreshResult.Refreshed)

        val snapshot = repository.observe(id).first()
        assertThat(snapshot.details).isEqualTo(repoDetails("octo/repo"))
        assertThat(snapshot.readme).isEqualTo(readme)
        assertThat(snapshot.fetchedAtMillis).isEqualTo(now.toEpochMilli())
    }

    @Test
    fun only_the_repositories_opened_last_are_kept() = runTest {
        // Regression: every repository ever opened stayed on the phone, README included, and the cache only grew.
        val opened = (1..DefaultRepoRepository.STORED_REPOS + 3).map { RepoId("octo", "repo$it") }
        opened.forEach { repo ->
            api.details[repo] = repoDetails(repo.fullName)
            now = now.plusSeconds(1)
            repository.refresh(repo)
        }

        assertThat(opened.take(3).map { repository.observe(it).first().details }).containsExactly(null, null, null)
        assertThat(repository.observe(opened[3]).first().details).isNotNull()
        assertThat(repository.observe(opened.last()).first().details).isNotNull()
    }

    @Test
    fun a_repo_without_readme_is_remembered_as_such() = runTest {
        api.details[id] = repoDetails("octo/repo")
        api.readmes[id] = null

        repository.refresh(id)

        val snapshot = repository.observe(id).first()
        assertThat(snapshot.readme).isNull()
        assertThat(snapshot.details).isNotNull()
    }

    @Test
    fun a_renamed_repo_is_followed_by_its_canonical_name() = runTest {
        // Regression: search rejects old names with 422, so later calls must use the canonical one.
        val moved = RepoId("newowner", "repo")
        api.details[id] = repoDetails("newowner/repo")
        api.readmes[moved] = readme

        repository.refresh(id)

        // The README is asked alongside the details, under the old name; the move costs one more request.
        assertThat(api.calls).containsAtLeast("repo:octo/repo", "readme:newowner/repo")
        assertThat(api.calls.last()).isEqualTo("readme:newowner/repo")
        assertThat(repository.observe(id).first().readme).isEqualTo(readme)
    }

    @Test
    fun a_recent_cache_is_not_refetched_unless_forced() = runTest {
        api.details[id] = repoDetails("octo/repo")
        repository.refresh(id)
        now = now.plusSeconds(10 * 60)

        assertThat(repository.refresh(id)).isEqualTo(RefreshResult.Fresh)
        assertThat(repository.refresh(id, force = true)).isEqualTo(RefreshResult.Refreshed)
    }

    @Test
    fun a_failed_refresh_keeps_the_cache() = runTest {
        api.details[id] = repoDetails("octo/repo")
        api.readmes[id] = readme
        repository.refresh(id)
        api.failure = ForgeError.Network

        assertThat(repository.refresh(id, force = true)).isEqualTo(RefreshResult.Failed(ForgeError.Network))
        assertThat(repository.observe(id).first().readme).isEqualTo(readme)
    }

    @Test
    fun signed_in_calls_use_the_token_and_signed_out_calls_are_anonymous() = runTest {
        api.details[id] = repoDetails("octo/repo")
        repository.refresh(id, force = true)
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "ghp_token")
        repository.refresh(id, force = true)

        assertThat(api.tokens.first()).isNull()
        assertThat(api.tokens.last()).isEqualTo("ghp_token")
    }

    @Test
    fun closed_lists_searches_and_pinned_issues_pass_through_to_the_forge() = runTest {
        val closed = issueSummary(3, "Old crash", state = fr.arthurbrugiere.forgeline.core.model.IssueState.CLOSED)
        api.issues = listOf(issueSummary(1, "Crash on start"), issueSummary(2, "Slow list"), closed)
        api.pinned = listOf(issueSummary(2, "Slow list"))

        assertThat(repository.issues(id, IssueQuery(open = false))).isEqualTo(ForgeResult.Success(listOf(closed)))
        assertThat(repository.issues(id, IssueQuery(text = "crash"))).isEqualTo(ForgeResult.Success(listOf(issueSummary(1, "Crash on start"))))
        repository.pullRequests(id, IssueQuery(open = false, text = "fix"))
        assertThat(repository.pinnedIssues(id)).isEqualTo(ForgeResult.Success(listOf(issueSummary(2, "Slow list"))))

        assertThat(api.calls).containsAtLeast("issues:octo/repo closed", "issues:octo/repo \"crash\"", "pulls:octo/repo closed \"fix\"", "pinned:octo/repo").inOrder()
    }

    private fun release(tag: String, prerelease: Boolean = false) = fr.arthurbrugiere.forgeline.core.model.Release(tag, null, "Notes of $tag", null, prerelease, null)

    @Test
    fun a_release_seen_in_its_list_opens_without_asking_the_forge() = runTest {
        api.releases = listOf(release("v2").copy(isLatest = true), release("v1"))
        assertThat(repository.cachedRelease(id, "v2")).isNull()

        repository.releases(id)

        assertThat(repository.cachedRelease(id, "v2")).isEqualTo(release("v2").copy(isLatest = true))
        assertThat(repository.cachedRelease(id, "v1")).isEqualTo(release("v1"))
        assertThat(repository.cachedRelease(id, "v0")).isNull()
        assertThat(api.calls.none { it.startsWith("release:") }).isTrue()
    }

    @Test
    fun a_release_loaded_alone_keeps_what_only_the_list_knew_of_it() = runTest {
        api.releases = listOf(release("v2").copy(isLatest = true), release("v1"))
        repository.releases(id)

        // Alone, the forge doesn't say whether it is the latest.
        val reloaded = (repository.release(id, "v2") as ForgeResult.Success).value

        assertThat(reloaded.isLatest).isTrue()
        assertThat((repository.release(id, "v1") as ForgeResult.Success).value.isLatest).isFalse()
        assertThat(api.calls).containsAtLeast("release:octo/repo@v2", "release:octo/repo@v1")
    }

    @Test
    fun a_release_never_listed_is_asked_of_the_forge_then_kept() = runTest {
        api.releases = listOf(release("v1"))

        assertThat(repository.release(id, "v1")).isEqualTo(ForgeResult.Success(release("v1")))
        assertThat(repository.cachedRelease(id, "v1")).isEqualTo(release("v1"))
        assertThat(repository.release(id, "nope")).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Not Found")))
    }

    @Test
    fun lists_pass_through_to_the_forge() = runTest {
        api.issues = emptyList()

        assertThat(repository.issues(id)).isEqualTo(ForgeResult.Success(emptyList<Any>()))
        repository.pullRequests(id)
        repository.releases(id)
        repository.workflowRuns(id)
        repository.contents(id, "src", "main")
        repository.fileText(id, "src/a.kt", "main")

        assertThat(api.calls).containsExactly(
            "issues:octo/repo", "pulls:octo/repo", "releases:octo/repo", "runs:octo/repo",
            "contents:octo/repo:src@main", "file:octo/repo:src/a.kt@main",
        ).inOrder()
    }

    @Test
    fun a_codeberg_repository_is_read_from_codeberg_with_its_account() = runTest {
        val cb = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)
        val codeberg = FakeRepoApi().apply { details[cb] = repoDetails("forgejo/forgejo").copy(id = cb) }
        val clients = FakeForgeClients(repos = api).apply { put(ForgeInstance.Codeberg, FakeForgeClients(repos = codeberg)) }
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "gh_token")
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb_token")
        val repository = DefaultRepoRepository(database.repoDao(), clients, accounts, clock)

        repository.refresh(cb, force = true)

        assertThat(codeberg.calls.first()).isEqualTo("repo:forgejo/forgejo")
        assertThat(codeberg.tokens.first()).isEqualTo("cb_token")
        assertThat(api.calls).isEmpty()
        // Cached apart from a GitHub repository of the same name.
        assertThat(repository.observe(cb).first().details?.id).isEqualTo(cb)
        assertThat(repository.observe(RepoId("forgejo", "forgejo")).first().details).isNull()
    }

    @Test
    fun a_repository_with_its_issues_switched_off_is_remembered_as_such() = runTest {
        api.details[id] = repoDetails("octo/repo").copy(hasIssues = false)

        repository.refresh(id)

        assertThat(repository.observe(id).first().details?.hasIssues).isFalse()
    }

    @Test
    fun a_repository_with_its_ci_switched_off_is_remembered_as_such() = runTest {
        // Without it in the cache, a Codeberg repository without Actions showed an Actions tab once reopened.
        api.details[id] = repoDetails("octo/repo").copy(hasActions = false)

        repository.refresh(id)

        assertThat(repository.observe(id).first().details?.hasActions).isFalse()
    }

    @Test
    fun a_repositorys_details_and_readme_are_asked_together() = runTest {
        // One round trip to a far forge instead of two.
        api.details[id] = repoDetails("octo/repo")
        api.readmes[id] = readme
        val together = Rendezvous(2)
        val meeting = object : RepoApi by api {
            override suspend fun repo(token: String?, id: RepoId) = together.arrive("repo").let { api.repo(token, id) }
            override suspend fun readme(token: String?, id: RepoId, ref: String?) = together.arrive("readme").let { api.readme(token, id, ref) }
        }
        val repository = DefaultRepoRepository(database.repoDao(), FakeForgeClients(repos = meeting), accounts, clock)

        assertThat(repository.refresh(id)).isEqualTo(RefreshResult.Refreshed)
        assertThat(repository.observe(id).first().readme).isEqualTo(readme)
    }

    @Test
    fun lists_are_remembered_for_the_session_once_the_forge_has_answered() = runTest {
        // Regression: a repository's tabs asked the forge behind a loading screen each time it was opened.
        api.issues = listOf(issueSummary(1, "Bug"))
        api.releases = listOf(release("v1"))
        assertThat(repository.rememberedIssues(id)).isNull()

        repository.issues(id)
        repository.pullRequests(id)
        repository.releases(id)
        repository.contents(id, "", "main")

        assertThat(repository.rememberedIssues(id)).containsExactly(issueSummary(1, "Bug"))
        assertThat(repository.rememberedPullRequests(id)).isEmpty()
        assertThat(repository.rememberedReleases(id)).hasSize(1)
        assertThat(repository.rememberedContents(id, "", "main")).isEmpty()
        // Each list is its own: the closed ones, another folder or another branch were never asked.
        assertThat(repository.rememberedIssues(id, IssueQuery(open = false))).isNull()
        assertThat(repository.rememberedContents(id, "docs", "main")).isNull()
        assertThat(repository.rememberedContents(id, "", "dev")).isNull()
        assertThat(repository.rememberedIssues(RepoId("octo", "other"))).isNull()
    }

    @Test
    fun a_list_that_failed_is_not_remembered_and_leaves_what_was() = runTest {
        api.issues = listOf(issueSummary(1, "Bug"))
        repository.issues(id)
        api.failure = ForgeError.Network

        repository.issues(id)
        repository.pullRequests(id)

        assertThat(repository.rememberedIssues(id)).containsExactly(issueSummary(1, "Bug"))
        assertThat(repository.rememberedPullRequests(id)).isNull()
    }

    @Test
    fun what_one_account_listed_is_not_shown_to_another() = runTest {
        val account = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "one")
        api.issues = listOf(issueSummary(1, "Private bug"))
        repository.issues(id)

        accounts.signOut(account.id)
        repository.releases(id)

        assertThat(repository.rememberedIssues(id)).isNull()
    }

    @Test
    fun how_many_issues_and_pull_requests_are_open_is_kept_with_a_repository() = runTest {
        api.details[id] = repoDetails("octo/repo").copy(openIssues = 12, openPulls = 3)

        repository.refresh(id)

        assertThat(repository.observe(id).first().details?.openIssues).isEqualTo(12)
        assertThat(repository.observe(id).first().details?.openPulls).isEqualTo(3)
    }

    @Test
    fun whether_a_repository_holds_discussions_and_a_wiki_is_kept_with_it() = runTest {
        api.details[id] = repoDetails("octo/repo").copy(hasDiscussions = true, hasWiki = true)

        repository.refresh(id)

        assertThat(repository.observe(id).first().details?.hasDiscussions).isTrue()
        assertThat(repository.observe(id).first().details?.hasWiki).isTrue()
    }

    @Test
    fun what_a_list_said_of_a_discussion_is_remembered_to_head_its_page() = runTest {
        val listed = fr.arthurbrugiere.forgeline.core.testing.discussionSummary(7, "How do I page?")
        api.discussionPages[null] = fr.arthurbrugiere.forgeline.core.model.DiscussionPage(listOf(listed), next = "c1")
        assertThat(repository.rememberedDiscussion(id, 7)).isNull()

        val page = (repository.discussions(id) as ForgeResult.Success).value

        assertThat(page.next).isEqualTo("c1")
        assertThat(repository.rememberedDiscussion(id, 7)).isEqualTo(listed)
        assertThat(repository.rememberedDiscussion(id, 8)).isNull()
    }

    @Test
    fun a_discussion_read_is_remembered_as_it_was_read() = runTest {
        val read = fr.arthurbrugiere.forgeline.core.testing.discussionSummary(7, "How do I page?", comments = 3)
        api.discussions[7] = fr.arthurbrugiere.forgeline.core.model.Discussion(read, "Body", emptyList())

        repository.discussion(id, 7)

        assertThat(repository.rememberedDiscussion(id, 7)).isEqualTo(read)
    }
}
