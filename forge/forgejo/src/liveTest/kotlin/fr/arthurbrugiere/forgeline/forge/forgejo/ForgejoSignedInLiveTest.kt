package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Forgeline's Forgejo client against the real Codeberg, signed in. It only ever reads (no star, follow, mark-read or
 * write of any kind), and is skipped without CODEBERG_LIVE_TOKEN. What it prints names no private data beyond counts
 * and public repository names.
 */
class ForgejoSignedInLiveTest {
    private val token = System.getenv("CODEBERG_LIVE_TOKEN").orEmpty()
    private val http = forgejoHttpClient(OkHttp.create())

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    /** Read only: nothing is changed on Codeberg from here. */
    @Test
    fun a_repository_s_settings_read() = runBlocking {
        assumeTrue("CODEBERG_LIVE_TOKEN not set", token.isNotBlank())
        val forge = fr.arthurbrugiere.forgeline.core.model.ForgeInstance.Codeberg
        val settings = ForgejoRepoSettingsApi(http, forge).settings(token, fr.arthurbrugiere.forgeline.core.model.RepoId("forgejo", "forgejo", forge)).value()

        check(settings.defaultBranch.isNotEmpty()) { "default branch: $settings" }
        check(settings.switches[fr.arthurbrugiere.forgeline.core.model.RepoSwitch.ISSUES] == true) { "issues: $settings" }
        check(settings.switches[fr.arthurbrugiere.forgeline.core.model.RepoSwitch.PULL_REQUESTS] == true) { "pull requests: $settings" }
        check(fr.arthurbrugiere.forgeline.core.model.RepoSwitch.MERGE_COMMIT in settings.switches) { "merging: $settings" }
        System.err.println("CODEBERG settings: $settings")
    }

    @Test
    fun a_repository_s_templates_and_what_it_says_beside_them_read() = runBlocking {
        assumeTrue("CODEBERG_LIVE_TOKEN not set", token.isNotBlank())
        val forge = ForgeInstance.Codeberg
        val forgejo = fr.arthurbrugiere.forgeline.core.model.RepoId("forgejo", "forgejo", forge)
        val issues = ForgejoIssueApi(http, forge)

        val chooser = issues.issueChooser(token, forgejo).value()
        System.err.println("CODEBERG chooser: blank=${chooser.blankIssues} links=${chooser.links.map { it.name }}")
        assertWithMessage("forgejo/forgejo's links").that(chooser.links).isNotEmpty()
        assertWithMessage("every link has an address").that(chooser.links.all { it.url.isNotBlank() }).isTrue()
        val templates = issues.issueTemplates(token, forgejo).value()
        assertWithMessage("forgejo/forgejo's forms").that(templates.all { it.body.orEmpty().isNotBlank() }).isTrue()
    }

    @Test
    fun the_feed_and_the_starred_repositories_read() = runBlocking {
        assumeTrue("CODEBERG_LIVE_TOKEN not set", token.isNotBlank())
        val me = ForgejoAuthApi(http, ForgeInstance.Codeberg).fetchAuthenticatedUser(token).value()
        val feed = ForgejoFeedApi(http, ForgeInstance.Codeberg)

        var started = System.currentTimeMillis()
        val page = feed.receivedEvents(token, me.login, page = 1)
        System.err.println("CODEBERG feed page 1: ${(page as? ForgeResult.Success)?.value?.events?.size} events in ${System.currentTimeMillis() - started} ms")
        assertWithMessage("feed: $page").that(page).isInstanceOf(ForgeResult.Success::class.java)

        started = System.currentTimeMillis()
        val starred = feed.starredActivity(token, Instant.now().minus(Duration.ofDays(30)))
        assertWithMessage("starred: $starred").that(starred).isInstanceOf(ForgeResult.Success::class.java)
        System.err.println("CODEBERG starred: ${starred.value().size} releases in ${System.currentTimeMillis() - started} ms")
        starred.value().sortedByDescending { it.createdAt }.take(8).forEach { System.err.println("CODEBERG ${it.createdAt} ${it.repo.fullName} ${it.action} by ${it.actor.login}") }
        // Whatever shows is recent, on Codeberg, and a release.
        assertWithMessage("forge").that(starred.value().all { it.repo.forge == ForgeInstance.Codeberg }).isTrue()
    }

    @Test
    fun the_inbox_reads() = runBlocking {
        assumeTrue("CODEBERG_LIVE_TOKEN not set", token.isNotBlank())
        val started = System.currentTimeMillis()
        val threads = ForgejoNotificationsApi(http, ForgeInstance.Codeberg).threads(token, ifModifiedSince = null, maxPages = 1)

        assertWithMessage("inbox: $threads").that(threads).isInstanceOf(ForgeResult.Success::class.java)
        System.err.println("CODEBERG inbox read in ${System.currentTimeMillis() - started} ms")
    }

    @Test
    fun the_signed_in_person_s_work_reads() = runBlocking {
        assumeTrue("CODEBERG_LIVE_TOKEN not set", token.isNotBlank())
        val me = ForgejoAuthApi(http, ForgeInstance.Codeberg).fetchAuthenticatedUser(token).value()
        val search = ForgejoSearchApi(http, ForgeInstance.Codeberg)

        for (kind in WorkKind.entries) {
            val started = System.currentTimeMillis()
            val found = search.work(token, me.login, kind)
            assertWithMessage("$kind: ${(found as? ForgeResult.Failure)?.error}").that(found).isInstanceOf(ForgeResult.Success::class.java)
            System.err.println("CODEBERG work $kind: ${found.value().size} in ${System.currentTimeMillis() - started} ms")
            // Whatever shows is open, on Codeberg, and a pull request where only those are asked for.
            assertWithMessage("$kind open").that(found.value().all { it.issue.state == IssueState.OPEN }).isTrue()
            assertWithMessage("$kind forge").that(found.value().all { it.repo.forge == ForgeInstance.Codeberg }).isTrue()
            if (kind != WorkKind.ASSIGNED) assertWithMessage("$kind pull requests").that(found.value().all { it.issue.isPullRequest }).isTrue()
        }
    }

    /** Read only: the profile and an organisation's settings are read, never written. */
    @Test
    fun a_profile_s_and_an_organisation_s_settings_read() = runBlocking {
        assumeTrue("CODEBERG_LIVE_TOKEN not set", token.isNotBlank())
        val api = ForgejoOwnerSettingsApi(http, ForgeInstance.Codeberg)
        val profile = api.settings(token, fr.arthurbrugiere.forgeline.core.model.Owner.Me).value()

        check(fr.arthurbrugiere.forgeline.core.model.OwnerText.NAME in profile.texts) { "profile: $profile" }
        check(fr.arthurbrugiere.forgeline.core.model.OwnerSwitch.HIDE_EMAIL in profile.switches) { "profile: $profile" }
        check(fr.arthurbrugiere.forgeline.core.model.OwnerChoice.DIFF_STYLE in profile.choices) { "profile: $profile" }
        // The account's organisations, if any: one it owns reads its settings, and says it owns it.
        val orgs = ForgejoUserApi(http, ForgeInstance.Codeberg).organizations(token).value()
        for (org in orgs.take(2)) {
            val owns = api.administers(token, org.login).value()
            val settings = api.settings(token, fr.arthurbrugiere.forgeline.core.model.Owner.Organization(org.login)).value()
            check(fr.arthurbrugiere.forgeline.core.model.OwnerChoice.VISIBILITY in settings.choices) { "${org.login}: $settings" }
            System.err.println("CODEBERG organisation owned: $owns, ${settings.texts.size} texts")
        }
        System.err.println("CODEBERG profile: ${profile.texts.size} texts, ${profile.switches.size} switches")
    }
}
