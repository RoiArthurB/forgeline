package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The Tangled client against Tangled's real index, without an account. Its calls are generated from Tangled's lexicons
 * (lexicons/, refreshed by tools/specs/fetch.py), some still under `org.tangled.temp`: if these fail, Tangled renamed or
 * reshaped one of them, or its index answers something its lexicons don't promise.
 */
class TangledLiveTest {
    private val http = tangledHttpClient(OkHttp.create())
    private val xrpc = TangledXrpc(http)
    private val identity = TangledIdentity(http, xrpc)

    /** Tangled's own code, on Tangled. */
    private val core = RepoId("tangled.org", "core", ForgeInstance.Tangled)

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun a_handle_resolves_to_a_did() = runBlocking<Unit> {
        assertThat(identity.did("tangled.org").value()).startsWith("did:")
    }

    @Test
    fun a_repository_its_readme_refs_and_files_read() = runBlocking<Unit> {
        val api = TangledRepoApi(xrpc, identity)

        val repo = api.repo(null, core).value()
        assertThat(repo.id).isEqualTo(core)
        assertThat(repo.stars).isGreaterThan(10)
        assertThat(api.languages(null, core).value()).isNotEmpty()
        assertThat(api.readme(null, core).value()?.markdown).isNotEmpty()
        val refs = api.refs(null, core).value()
        assertThat(refs.branches).contains(repo.defaultBranch)
        val root = api.contents(null, core, "", repo.defaultBranch).value()
        assertThat(root.map { it.path }).contains("go.mod")
        assertThat(root.first().type).isEqualTo(RepoFileType.DIR)
        assertThat(api.fileText(null, core, "go.mod", repo.defaultBranch).value()).contains("module ")
    }

    @Test
    fun trending_lists_repositories() = runBlocking<Unit> {
        val trending = TangledTrendingApi(xrpc, identity).trending(TrendingPeriod.MONTHLY).value()

        assertThat(trending).isNotEmpty()
        assertThat(trending.all { it.id.forge == ForgeInstance.Tangled }).isTrue()
    }

    @Test
    fun a_person_and_their_repositories_read() = runBlocking<Unit> {
        val users = TangledUserApi(xrpc, identity)

        assertThat(users.user(null, "tangled.org").value().login).isEqualTo("tangled.org")
        assertThat(users.repos(null, "tangled.org", mine = false, organization = false).value().map { it.id.name }).contains("core")
    }

    @Test
    fun a_profile_record_reads_and_people_have_their_pictures() = runBlocking<Unit> {
        // oppi.li keeps a Tangled profile with a description (as of 2026-11-05), and a picture on Bluesky or Tangled.
        val profile = TangledUserApi(xrpc, identity).user(null, "oppi.li").value()

        assertThat(profile.bio).isNotEmpty()
        assertThat(profile.avatarUrl).startsWith("https://")
        // Trending's owners are named with a picture's CID, or nothing: most have one.
        val trending = TangledTrendingApi(xrpc, identity).trending(TrendingPeriod.MONTHLY).value()
        assertThat(trending.count { it.ownerAvatarUrl != null }).isGreaterThan(0)
    }
}
