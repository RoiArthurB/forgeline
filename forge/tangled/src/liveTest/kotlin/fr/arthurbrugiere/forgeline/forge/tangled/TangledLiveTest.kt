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
 * The Tangled client against Tangled's real index, without an account. Its queries were written from Tangled's lexicons
 * (2026-11-02), some still under `org.tangled.temp`: if these fail, Tangled renamed or reshaped one of them.
 */
class TangledLiveTest {
    private val http = tangledHttpClient(OkHttp.create())
    private val identity = TangledIdentity(http)

    /** Tangled's own code, on Tangled. */
    private val core = RepoId("tangled.org", "core", ForgeInstance.Tangled)

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun a_handle_resolves_to_a_did() = runBlocking<Unit> {
        assertThat(identity.did("tangled.org").value()).startsWith("did:")
    }

    @Test
    fun a_repository_its_readme_refs_and_files_read() = runBlocking<Unit> {
        val api = TangledRepoApi(http, identity)

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
        val trending = TangledTrendingApi(http, identity).trending(TrendingPeriod.MONTHLY).value()

        assertThat(trending).isNotEmpty()
        assertThat(trending.all { it.id.forge == ForgeInstance.Tangled }).isTrue()
    }

    @Test
    fun a_person_and_their_repositories_read() = runBlocking<Unit> {
        val users = TangledUserApi(http, identity)

        assertThat(users.user(null, "tangled.org").value().login).isEqualTo("tangled.org")
        assertThat(users.repos(null, "tangled.org", mine = false, organization = false).value().map { it.id.name }).contains("core")
    }
}
