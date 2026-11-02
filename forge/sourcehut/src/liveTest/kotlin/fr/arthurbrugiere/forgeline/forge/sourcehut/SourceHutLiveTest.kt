package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The SourceHut client against the real git.sr.ht. Its queries were written from git.sr.ht's schema and resolvers
 * (2026-11-02); SourceHut answers nothing without a token, so these need SOURCEHUT_LIVE_TOKEN (a personal access token,
 * read-only is enough) and are skipped without it.
 */
class SourceHutLiveTest {
    private val token = System.getenv("SOURCEHUT_LIVE_TOKEN").orEmpty()
    private val http = sourceHutHttpClient(OkHttp.create())

    /** A small, long-lived repository with tags and signed release files. */
    private val scdoc = RepoId("~sircmpwn", "scdoc", ForgeInstance.SourceHut)

    private fun <T> ForgeResult<T>.value(): T = if (this is ForgeResult.Success) value else error("$this")

    @Before
    fun needsAToken() = assumeTrue("SOURCEHUT_LIVE_TOKEN not set", token.isNotBlank())

    @Test
    fun the_token_says_who_it_is() = runBlocking<Unit> {
        assertThat(SourceHutAuthApi(http).fetchAuthenticatedUser(token).value().login).startsWith("~")
    }

    @Test
    fun a_repository_its_readme_refs_files_and_releases_read() = runBlocking<Unit> {
        val api = SourceHutRepoApi(http)

        val repo = api.repo(token, scdoc).value()
        assertThat(repo.description).isNotEmpty()
        assertThat(api.readme(token, scdoc).value()?.markdown).isNotEmpty()
        val refs = api.refs(token, scdoc).value()
        assertThat(refs.branches).contains(repo.defaultBranch)
        assertThat(refs.tags).isNotEmpty()
        val root = api.contents(token, scdoc, "", repo.defaultBranch).value()
        assertThat(root.map { it.path }).contains("Makefile")
        assertThat(root.filter { it.type == RepoFileType.DIR }.map { it.name }).contains("src")
        assertThat(api.contents(token, scdoc, "src", repo.defaultBranch).value()).isNotEmpty()
        assertThat(api.fileText(token, scdoc, "Makefile", repo.defaultBranch).value()).isNotEmpty()
        val releases = api.releases(token, scdoc).value()
        assertThat(releases.first().publishedAt).isNotNull()
        assertThat(api.release(token, scdoc, releases.first().tag).value().tag).isEqualTo(releases.first().tag)
    }

    @Test
    fun a_person_and_their_repositories_read() = runBlocking<Unit> {
        val users = SourceHutUserApi(http)

        assertThat(users.user(token, "~sircmpwn").value().login).isEqualTo("~sircmpwn")
        assertThat(users.repos(token, "~sircmpwn", mine = false, organization = false).value().map { it.id.name }).contains("scdoc")
    }
}
