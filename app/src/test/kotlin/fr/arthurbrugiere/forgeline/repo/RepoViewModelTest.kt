package fr.arthurbrugiere.forgeline.repo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.repo.RepoSnapshot
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeStarRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.core.testing.repoDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repos = FakeRepoRepository()
    private val stars = FakeStarRepository()
    private val accounts = FakeAccountRepository()
    private val requested = RepoId("octo", "repo")
    private val details = repoDetails("octo/repo", defaultBranch = "master")

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun TestScope.viewModel() = RepoViewModel(requested, repos, stars, accounts).also { it.state.launchIn(backgroundScope) }

    private fun cache(readme: Readme? = Readme("README.md", "# Hi"), repo: fr.arthurbrugiere.forgeline.core.model.RepoDetails = details) {
        repos.snapshot.value = RepoSnapshot(repo, readme, 1_000)
    }

    @Test
    fun shows_the_cached_repo_and_refreshes_it_if_stale() = test {
        cache()

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.details).isEqualTo(details)
        assertThat(viewModel.state.value.readme?.markdown).isEqualTo("# Hi")
        assertThat(viewModel.state.value.tab).isEqualTo(RepoTab.README)
        assertThat(repos.refreshes).containsExactly(false)
    }

    @Test
    fun readme_paths_resolve_against_the_default_branch_and_readme_folder() = test {
        cache(readme = Readme("docs/README.md", "# Docs"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.readmeContext).isEqualTo(
            ReadmeContext(
                rawBaseUrl = "https://raw.example/octo/repo/master/",
                blobBaseUrl = "https://blob.example/octo/repo/master/",
                directory = "docs/",
            ),
        )
    }

    @Test
    fun a_tab_loads_the_first_time_it_is_opened_only() = test {
        cache()
        repos.issues = ForgeResult.Success(listOf(issueSummary(1, "Bug")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectTab(RepoTab.ISSUES)
        advanceUntilIdle()
        viewModel.selectTab(RepoTab.README)
        viewModel.selectTab(RepoTab.ISSUES)
        advanceUntilIdle()

        assertThat(viewModel.state.value.issues).isEqualTo(Loadable.Loaded(listOf(issueSummary(1, "Bug"))))
        assertThat(repos.calls.count { it.startsWith("issues:") }).isEqualTo(1)
    }

    @Test
    fun tab_calls_use_the_canonical_name_of_a_moved_repo() = test {
        // Regression: search rejects a moved repo's old name with 422.
        cache(repo = repoDetails("newowner/repo"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectTab(RepoTab.ISSUES)
        viewModel.selectTab(RepoTab.PULLS)
        viewModel.selectTab(RepoTab.RELEASES)
        viewModel.selectTab(RepoTab.ACTIONS)
        viewModel.selectTab(RepoTab.CODE)
        advanceUntilIdle()

        assertThat(repos.calls).isNotEmpty()
        assertThat(repos.calls.all { "newowner/repo" in it }).isTrue()
    }

    @Test
    fun a_tab_opened_before_details_arrive_loads_once_they_do() = test {
        val viewModel = viewModel()
        viewModel.selectTab(RepoTab.PULLS)
        advanceUntilIdle()
        assertThat(repos.calls).isEmpty()

        cache()
        advanceUntilIdle()

        assertThat(repos.calls).containsExactly("pulls:octo/repo")
    }

    @Test
    fun a_failed_tab_reports_its_error_and_can_be_retried() = test {
        cache()
        repos.releases = ForgeResult.Failure(ForgeError.Network)
        val viewModel = viewModel()
        viewModel.selectTab(RepoTab.RELEASES)
        advanceUntilIdle()

        assertThat(viewModel.state.value.releases).isEqualTo(Loadable.Failed(ForgeError.Network))

        repos.releases = ForgeResult.Success(emptyList())
        viewModel.retryTab()
        advanceUntilIdle()

        assertThat(viewModel.state.value.releases).isEqualTo(Loadable.Loaded(emptyList<Any>()))
    }

    @Test
    fun the_code_tab_browses_folders_at_the_default_branch() = test {
        cache()
        val src = RepoFile("src", "src", RepoFileType.DIR, 0)
        repos.directories = mapOf(
            "" to ForgeResult.Success(listOf(src)),
            "src" to ForgeResult.Success(listOf(RepoFile("src/Main.kt", "Main.kt", RepoFileType.FILE, 10))),
        )
        val viewModel = viewModel()
        viewModel.selectTab(RepoTab.CODE)
        advanceUntilIdle()

        assertThat(viewModel.state.value.code.path).isEmpty()
        viewModel.openDirectory("src")
        advanceUntilIdle()
        assertThat(viewModel.state.value.code.path).isEqualTo("src")
        assertThat((viewModel.state.value.code.entries as Loadable.Loaded).value.single().name).isEqualTo("Main.kt")

        viewModel.openParentDirectory()
        advanceUntilIdle()
        assertThat(viewModel.state.value.code.path).isEmpty()
        assertThat(repos.calls).containsExactly("contents:octo/repo:@master", "contents:octo/repo:src@master", "contents:octo/repo:@master")
    }

    @Test
    fun branches_and_tags_load_once_when_asked_for() = test {
        cache()
        repos.refs = ForgeResult.Success(GitRefs(listOf("dev", "master"), listOf("v1.0")))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.refs).isEqualTo(Loadable.Idle)

        viewModel.loadRefs()
        advanceUntilIdle()
        viewModel.loadRefs()
        advanceUntilIdle()

        assertThat(viewModel.state.value.refs).isEqualTo(Loadable.Loaded(GitRefs(listOf("dev", "master"), listOf("v1.0"))))
        assertThat(repos.calls.filter { it.startsWith("refs:") }).containsExactly("refs:octo/repo")
    }

    @Test
    fun failed_refs_load_again_next_time() = test {
        cache()
        repos.refs = ForgeResult.Failure(ForgeError.Network)
        val viewModel = viewModel()
        viewModel.loadRefs()
        advanceUntilIdle()
        assertThat(viewModel.state.value.refs).isEqualTo(Loadable.Failed(ForgeError.Network))

        repos.refs = ForgeResult.Success(GitRefs(listOf("master"), emptyList()))
        viewModel.loadRefs()
        advanceUntilIdle()

        assertThat(viewModel.state.value.refs).isEqualTo(Loadable.Loaded(GitRefs(listOf("master"), emptyList())))
    }

    @Test
    fun another_branch_shows_its_readme_and_code() = test {
        cache()
        repos.readmes["v1.0"] = ForgeResult.Success(Readme("README.md", "# Old"))
        repos.directories = mapOf("" to ForgeResult.Success(listOf(RepoFile("src", "src", RepoFileType.DIR, 0))))
        val viewModel = viewModel()
        viewModel.selectTab(RepoTab.CODE)
        advanceUntilIdle()
        viewModel.openDirectory("src")
        advanceUntilIdle()

        viewModel.selectRef("v1.0")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.browsedRef).isEqualTo("v1.0")
        assertThat(state.readme?.markdown).isEqualTo("# Old")
        assertThat(state.readmeContext?.rawBaseUrl).isEqualTo("https://raw.example/octo/repo/v1.0/")
        // A folder may not exist on the other ref: browsing starts again at the root.
        assertThat(state.code.path).isEmpty()
        assertThat(repos.calls.last()).isEqualTo("contents:octo/repo:@v1.0")
        assertThat(repos.calls).contains("readme:octo/repo@v1.0")
    }

    @Test
    fun back_on_the_default_branch_the_cached_readme_returns() = test {
        cache()
        repos.readmes["dev"] = ForgeResult.Success(Readme("README.md", "# Dev"))
        val viewModel = viewModel()
        viewModel.selectRef("dev")
        advanceUntilIdle()

        viewModel.selectRef("master")
        advanceUntilIdle()

        assertThat(viewModel.state.value.ref).isNull()
        assertThat(viewModel.state.value.browsedRef).isEqualTo("master")
        assertThat(viewModel.state.value.readme?.markdown).isEqualTo("# Hi")
        assertThat(repos.calls.filter { it.startsWith("readme:") }).containsExactly("readme:octo/repo@dev")
    }

    @Test
    fun a_readme_that_failed_on_another_branch_can_be_retried() = test {
        cache()
        repos.readmes["dev"] = ForgeResult.Failure(ForgeError.Network)
        val viewModel = viewModel()
        viewModel.selectRef("dev")
        advanceUntilIdle()
        assertThat(viewModel.state.value.refReadme).isEqualTo(Loadable.Failed(ForgeError.Network))

        repos.readmes["dev"] = ForgeResult.Success(Readme("README.md", "# Dev"))
        viewModel.retryTab()
        advanceUntilIdle()

        assertThat(viewModel.state.value.readme?.markdown).isEqualTo("# Dev")
    }

    @Test
    fun pull_to_refresh_forces_the_repo_and_reloads_the_open_tab() = test {
        cache()
        val viewModel = viewModel()
        viewModel.selectTab(RepoTab.ISSUES)
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(repos.refreshes).containsExactly(false, true).inOrder()
        assertThat(repos.calls.count { it.startsWith("issues:") }).isEqualTo(2)
    }

    @Test
    fun without_a_cache_a_failed_refresh_is_an_error() = test {
        repos.nextRefresh = RefreshResult.Failed(ForgeError.Http(404, "Not Found"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Http(404, "Not Found"))
        assertThat(viewModel.state.value.details).isNull()
    }

    @Test
    fun signed_in_the_star_state_loads_and_toggles() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t")
        cache()
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.starred).isFalse()

        viewModel.toggleStar()
        runCurrent()

        assertThat(viewModel.state.value.starred).isTrue()
        advanceUntilIdle()
        assertThat(stars.starred).containsExactly(requested)
    }

    @Test
    fun the_actions_tab_shows_unless_the_repository_switched_its_ci_off() {
        val codeberg = RepoId("forgejo", "website", ForgeInstance.Codeberg)
        val details = repoDetails("forgejo/website").copy(id = codeberg)

        assertThat(RepoUiState(codeberg).tabs).contains(RepoTab.ACTIONS)
        assertThat(RepoUiState(codeberg, details = details).tabs).contains(RepoTab.ACTIONS)
        assertThat(RepoUiState(codeberg, details = details.copy(hasActions = false)).tabs).doesNotContain(RepoTab.ACTIONS)
    }
}
