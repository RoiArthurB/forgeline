package fr.arthurbrugiere.forgeline.repo

import fr.arthurbrugiere.forgeline.core.testing.discussionSummary
import fr.arthurbrugiere.forgeline.core.model.DiscussionPage
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.repo.RepoSnapshot
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.InMemoryConversationDao
import java.time.Clock
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeStarRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.core.testing.repoDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueState
import kotlinx.coroutines.test.advanceTimeBy
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

    private val issueApi = FakeIssueApi()
    private val conversations = DefaultIssueRepository(FakeForgeClients(issues = issueApi), accounts, InMemoryConversationDao(), Clock.systemUTC())

    private fun TestScope.viewModel() = RepoViewModel(requested, repos, stars, accounts, conversations).also { it.state.launchIn(backgroundScope) }

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
    fun an_issue_opened_from_the_app_reloads_the_list_already_shown() = test {
        cache()
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        val viewModel = viewModel()
        viewModel.selectTab(RepoTab.ISSUES)
        advanceUntilIdle()

        conversations.create(details.id, "Crash", "Steps")
        advanceUntilIdle()

        assertThat(repos.calls.count { it.startsWith("issues:") }).isEqualTo(2)
    }

    @Test
    fun a_conversation_closed_from_the_app_reloads_the_issues_and_pull_requests_shown() = test {
        cache()
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        val viewModel = viewModel()
        viewModel.selectTab(RepoTab.ISSUES)
        viewModel.selectTab(RepoTab.PULLS)
        advanceUntilIdle()

        conversations.setOpen(IssueRef(details.id, 3), open = false)
        advanceUntilIdle()

        assertThat(repos.calls.count { it.startsWith("issues:") }).isEqualTo(2)
        assertThat(repos.calls.count { it.startsWith("pulls:") }).isEqualTo(2)
    }

    @Test
    fun an_issue_opened_elsewhere_or_before_the_list_was_asked_loads_nothing() = test {
        cache()
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        viewModel()
        advanceUntilIdle()

        // The Issues tab was never opened: it loads when it is.
        conversations.create(details.id, "Crash", "Steps")
        conversations.create(RepoId("octo", "other"), "Crash", "Steps")
        advanceUntilIdle()

        assertThat(repos.calls.count { it.startsWith("issues:") }).isEqualTo(0)
    }

    private val closed = issueSummary(3, "Old crash", state = IssueState.CLOSED)

    private fun TestScope.onIssues(): RepoViewModel {
        cache()
        repos.issues = ForgeResult.Success(listOf(issueSummary(1, "Bug")))
        return viewModel().also {
            it.selectTab(RepoTab.ISSUES)
            advanceUntilIdle()
        }
    }

    private val issueCalls get() = repos.calls.filter { it.startsWith("issues:") }

    @Test
    fun the_closed_issues_are_listed_on_demand_and_the_open_ones_come_back() = test {
        repos.answers[IssueQuery(open = false)] = ForgeResult.Success(listOf(closed))
        val viewModel = onIssues()

        // The forge takes its time: the list it replaces gives way at once.
        repos.gate = kotlinx.coroutines.CompletableDeferred()
        viewModel.showOpen(false)
        runCurrent()
        assertThat(viewModel.state.value.issues).isEqualTo(Loadable.Loading)
        repos.gate?.complete(Unit)
        repos.gate = null
        advanceUntilIdle()
        assertThat(viewModel.state.value.issueQuery).isEqualTo(IssueQuery(open = false))
        assertThat(viewModel.state.value.issues).isEqualTo(Loadable.Loaded(listOf(closed)))

        viewModel.showOpen(true)
        advanceUntilIdle()

        assertThat(viewModel.state.value.issues).isEqualTo(Loadable.Loaded(listOf(issueSummary(1, "Bug"))))
        assertThat(issueCalls).containsExactly("issues:octo/repo", "issues:octo/repo closed", "issues:octo/repo").inOrder()
    }

    @Test
    fun asking_for_what_is_already_listed_loads_nothing() = test {
        val viewModel = onIssues()

        viewModel.showOpen(true)
        viewModel.search("")
        advanceUntilIdle()

        assertThat(issueCalls).hasSize(1)
    }

    @Test
    fun a_search_is_sent_once_the_writing_pauses_not_for_every_letter() = test {
        repos.answers[IssueQuery(text = "crash")] = ForgeResult.Success(listOf(issueSummary(9, "Crash on start")))
        val viewModel = onIssues()

        "cras".fold("") { written, letter -> (written + letter).also(viewModel::search) }
        advanceTimeBy(RepoViewModel.SEARCH_PAUSE_MILLIS - 1)
        viewModel.search("crash")
        runCurrent()
        // The field shows what is written at once; nothing is asked yet.
        assertThat(viewModel.state.value.issueQuery.text).isEqualTo("crash")
        assertThat(issueCalls).hasSize(1)
        advanceUntilIdle()

        assertThat(issueCalls).containsExactly("issues:octo/repo", "issues:octo/repo \"crash\"").inOrder()
        assertThat(viewModel.state.value.issues).isEqualTo(Loadable.Loaded(listOf(issueSummary(9, "Crash on start"))))
    }

    @Test
    fun a_search_keeps_to_the_open_or_closed_ones_chosen() = test {
        val viewModel = onIssues()
        viewModel.showOpen(false)
        advanceUntilIdle()

        viewModel.search("crash")
        advanceUntilIdle()

        assertThat(issueCalls.last()).isEqualTo("issues:octo/repo closed \"crash\"")
    }

    @Test
    fun a_list_that_answers_after_another_was_asked_for_is_dropped() = test {
        repos.answers[IssueQuery(open = false)] = ForgeResult.Success(listOf(closed))
        val viewModel = onIssues()
        repos.gate = kotlinx.coroutines.CompletableDeferred()

        viewModel.showOpen(false)
        runCurrent()
        viewModel.showOpen(true)
        runCurrent()
        repos.gate?.complete(Unit)
        advanceUntilIdle()

        // Both answers came; only the one for what is asked now is shown.
        assertThat(viewModel.state.value.issueQuery).isEqualTo(IssueQuery())
        assertThat(viewModel.state.value.issues).isEqualTo(Loadable.Loaded(listOf(issueSummary(1, "Bug"))))
    }

    @Test
    fun issues_and_pull_requests_are_filtered_apart() = test {
        val viewModel = onIssues()
        viewModel.showOpen(false)
        advanceUntilIdle()

        viewModel.selectTab(RepoTab.PULLS)
        advanceUntilIdle()
        viewModel.search("fix")
        advanceUntilIdle()

        assertThat(viewModel.state.value.issueQuery).isEqualTo(IssueQuery(open = false))
        assertThat(viewModel.state.value.pullQuery).isEqualTo(IssueQuery(text = "fix"))
        assertThat(repos.calls.filter { it.startsWith("pulls:") }).containsExactly("pulls:octo/repo", "pulls:octo/repo \"fix\"").inOrder()
    }

    @Test
    fun the_pinned_issues_load_with_the_plain_list_only() = test {
        repos.pinned = ForgeResult.Success(listOf(issueSummary(2, "Read before reporting")))
        val viewModel = onIssues()
        assertThat(viewModel.state.value.pinned).containsExactly(issueSummary(2, "Read before reporting"))

        viewModel.showOpen(false)
        advanceUntilIdle()
        viewModel.search("crash")
        advanceUntilIdle()

        assertThat(repos.calls.count { it.startsWith("pinned:") }).isEqualTo(1)
    }

    @Test
    fun pinned_issues_that_fail_to_load_leave_the_list_as_it_is() = test {
        repos.pinned = ForgeResult.Failure(ForgeError.Network)

        val viewModel = onIssues()

        assertThat(viewModel.state.value.pinned).isEmpty()
        assertThat(viewModel.state.value.issues).isEqualTo(Loadable.Loaded(listOf(issueSummary(1, "Bug"))))
    }

    @Test
    fun a_filter_asked_on_another_tab_changes_nothing() = test {
        cache()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.showOpen(false)
        viewModel.search("crash")
        advanceUntilIdle()

        assertThat(viewModel.state.value.issueQuery).isEqualTo(IssueQuery())
        assertThat(viewModel.state.value.pullQuery).isEqualTo(IssueQuery())
        assertThat(repos.calls.none { it.startsWith("issues:") || it.startsWith("pulls:") }).isTrue()
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

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t")

    @Test
    fun signed_in_what_is_watched_loads_and_toggles() = test {
        signIn()
        cache()
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.watching).isFalse()

        viewModel.toggleWatch()
        runCurrent()

        assertThat(viewModel.state.value.watching).isTrue()
        advanceUntilIdle()
        assertThat(stars.watched).containsExactly(requested)

        viewModel.toggleWatch()
        advanceUntilIdle()
        assertThat(stars.watched).isEmpty()
        assertThat(viewModel.state.value.watching).isFalse()
    }

    @Test
    fun signed_out_or_where_the_forge_can_t_say_watching_is_unknown_and_toggling_does_nothing() = test {
        cache()
        val signedOut = viewModel()
        advanceUntilIdle()
        assertThat(signedOut.state.value.watching).isNull()

        signIn()
        stars.watchKnown = false
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleWatch()
        advanceUntilIdle()

        assertThat(viewModel.state.value.watching).isNull()
        assertThat(stars.watched).isEmpty()
    }

    @Test
    fun a_watch_that_fails_goes_back_and_says_so_once() = test {
        signIn()
        cache()
        val viewModel = viewModel()
        advanceUntilIdle()
        stars.setFailure = ForgeError.Network

        viewModel.toggleWatch()
        advanceUntilIdle()

        assertThat(viewModel.state.value.watching).isFalse()
        assertThat(viewModel.state.value.watchFailed).isTrue()
        viewModel.watchFailureShown()
        runCurrent()
        assertThat(viewModel.state.value.watchFailed).isFalse()
    }

    @Test
    fun a_fork_says_it_is_being_made_then_where_it_is() = test {
        signIn()
        cache()
        stars.forkedTo = RepoId("me", "repo")
        stars.gate = kotlinx.coroutines.CompletableDeferred()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.fork()
        // A second tap while the first is on its way asks for nothing more.
        viewModel.fork()
        advanceUntilIdle()
        assertThat(viewModel.state.value.isForking).isTrue()

        stars.gate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.state.value.isForking).isFalse()
        assertThat(viewModel.state.value.forkedTo).isEqualTo(RepoId("me", "repo"))
        assertThat(stars.forks).containsExactly(requested)
        viewModel.forkOpened()
        runCurrent()
        assertThat(viewModel.state.value.forkedTo).isNull()
    }

    @Test
    fun a_fork_that_is_refused_says_why_once() = test {
        signIn()
        cache()
        stars.forkFailure = ForgeError.Http(409, null)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.fork()
        advanceUntilIdle()

        assertThat(viewModel.state.value.isForking).isFalse()
        assertThat(viewModel.state.value.forkError).isEqualTo(ForgeError.Http(409, null))
        viewModel.forkErrorShown()
        runCurrent()
        assertThat(viewModel.state.value.forkError).isNull()
    }

    @Test
    fun a_fork_is_made_of_the_repository_s_current_name() = test {
        // The repository was renamed since the link to it was written: the forge is asked under its new name.
        signIn()
        cache(repo = repoDetails("octo/renamed"))
        stars.forkedTo = RepoId("me", "renamed")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.fork()
        advanceUntilIdle()

        assertThat(stars.forks).containsExactly(RepoId("octo", "renamed"))
    }

    private fun page(from: Int, size: Int = IssueQuery.PAGE_SIZE) = (from until from + size).map { issueSummary(it, "Issue $it") }

    @Test
    fun a_full_first_page_has_another_after_it_and_a_short_one_ends_the_list() = test {
        cache()
        repos.issues = ForgeResult.Success(page(1))
        repos.pulls = ForgeResult.Success(page(1, size = 4))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectTab(RepoTab.ISSUES)
        advanceUntilIdle()
        assertThat(viewModel.state.value.issuePaging).isEqualTo(ListPaging(next = 2))

        viewModel.selectTab(RepoTab.PULLS)
        advanceUntilIdle()
        assertThat(viewModel.state.value.pullPaging).isEqualTo(ListPaging())
    }

    @Test
    fun the_next_page_is_added_under_the_list_until_a_page_comes_short() = test {
        // Regression: the lists stopped at their first page.
        cache()
        repos.issues = ForgeResult.Success(page(1))
        repos.answers[IssueQuery(page = 2)] = ForgeResult.Success(page(31))
        repos.answers[IssueQuery(page = 3)] = ForgeResult.Success(page(61, size = 5))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectTab(RepoTab.ISSUES)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()
        assertThat((viewModel.state.value.issues as Loadable.Loaded).value.map { it.number }).isEqualTo((1..60).toList())
        assertThat(viewModel.state.value.issuePaging).isEqualTo(ListPaging(next = 3))

        viewModel.loadMore()
        advanceUntilIdle()
        assertThat((viewModel.state.value.issues as Loadable.Loaded).value).hasSize(65)
        assertThat(viewModel.state.value.issuePaging.next).isNull()

        // Nothing is left to ask for.
        viewModel.loadMore()
        advanceUntilIdle()
        assertThat(repos.calls.count { it.startsWith("issues:") }).isEqualTo(3)
    }

    @Test
    fun a_page_is_asked_for_once_however_often_the_end_is_reached() = test {
        cache()
        repos.issues = ForgeResult.Success(page(1))
        repos.answers[IssueQuery(page = 2)] = ForgeResult.Success(page(31, size = 3))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectTab(RepoTab.ISSUES)
        advanceUntilIdle()

        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(repos.calls.count { it.endsWith("page 2") }).isEqualTo(1)
    }

    @Test
    fun an_issue_that_moved_down_a_page_meanwhile_is_not_listed_twice() = test {
        cache()
        repos.issues = ForgeResult.Success(page(1))
        repos.answers[IssueQuery(page = 2)] = ForgeResult.Success(listOf(issueSummary(30, "Issue 30"), issueSummary(31, "Issue 31")))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectTab(RepoTab.ISSUES)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertThat((viewModel.state.value.issues as Loadable.Loaded).value.map { it.number }).isEqualTo((1..31).toList())
    }

    @Test
    fun a_page_that_couldn_t_be_loaded_says_so_and_can_be_asked_for_again() = test {
        cache()
        repos.issues = ForgeResult.Success(page(1))
        repos.answers[IssueQuery(page = 2)] = ForgeResult.Failure(ForgeError.Network)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectTab(RepoTab.ISSUES)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()
        assertThat(viewModel.state.value.issuePaging).isEqualTo(ListPaging(next = 2, failed = true))
        assertThat((viewModel.state.value.issues as Loadable.Loaded).value).hasSize(30)

        repos.answers[IssueQuery(page = 2)] = ForgeResult.Success(page(31, size = 2))
        viewModel.loadMore()
        advanceUntilIdle()
        assertThat((viewModel.state.value.issues as Loadable.Loaded).value).hasSize(32)
        assertThat(viewModel.state.value.issuePaging).isEqualTo(ListPaging())
    }

    @Test
    fun the_closed_ones_and_a_search_are_paged_too_and_a_page_for_a_list_left_behind_is_dropped() = test {
        cache()
        repos.answers[IssueQuery(open = false)] = ForgeResult.Success(page(100))
        repos.answers[IssueQuery(open = false, page = 2)] = ForgeResult.Success(page(130, size = 2))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectTab(RepoTab.ISSUES)
        viewModel.showOpen(false)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()
        assertThat((viewModel.state.value.issues as Loadable.Loaded).value).hasSize(32)
        assertThat(repos.calls).contains("issues:octo/repo closed page 2")

        // Back to the open ones while a page of the closed ones is on its way.
        repos.answers[IssueQuery(open = false)] = ForgeResult.Success(page(100))
        viewModel.showOpen(false)
        viewModel.showOpen(true)
        advanceUntilIdle()
        assertThat((viewModel.state.value.issues as Loadable.Loaded).value).isEmpty()
        assertThat(viewModel.state.value.issuePaging).isEqualTo(ListPaging())
    }

    @Test
    fun what_the_session_remembers_of_a_tab_shows_at_once_then_gives_way_to_the_forge_s_answer() = test {
        // Regression: every tab opened on a loading screen, however recently the repository had been read.
        cache()
        repos.remembered["issues"] = listOf(issueSummary(1, "Old"))
        repos.issues = ForgeResult.Success(listOf(issueSummary(2, "New"), issueSummary(1, "Old")))
        repos.gate = kotlinx.coroutines.CompletableDeferred()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectTab(RepoTab.ISSUES)
        advanceUntilIdle()
        assertThat(viewModel.state.value.issues).isEqualTo(Loadable.Loaded(listOf(issueSummary(1, "Old"))))

        repos.gate!!.complete(Unit)
        advanceUntilIdle()
        assertThat((viewModel.state.value.issues as Loadable.Loaded).value.map { it.number }).containsExactly(2, 1).inOrder()
    }

    @Test
    fun what_is_remembered_stays_when_the_forge_can_t_be_asked() = test {
        cache()
        repos.remembered["releases"] = listOf(fr.arthurbrugiere.forgeline.core.model.Release("v1", null, "Notes", null, false, null))
        repos.releases = ForgeResult.Failure(ForgeError.Network)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectTab(RepoTab.RELEASES)
        advanceUntilIdle()

        assertThat((viewModel.state.value.releases as Loadable.Loaded).value).hasSize(1)
    }

    @Test
    fun a_folder_read_before_shows_at_once_on_the_branch_it_was_read_on() = test {
        cache()
        val file = fr.arthurbrugiere.forgeline.core.model.RepoFile("docs/a.md", "a.md", fr.arthurbrugiere.forgeline.core.model.RepoFileType.FILE, 1)
        repos.remembered["contents:docs@master"] = listOf(file)
        repos.gate = kotlinx.coroutines.CompletableDeferred()
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectTab(RepoTab.CODE)

        viewModel.openDirectory("docs")
        runCurrent()

        assertThat(viewModel.state.value.code.entries).isEqualTo(Loadable.Loaded(listOf(file)))
    }

    private val withDiscussions = details.copy(hasDiscussions = true)

    @Test
    fun the_discussions_tab_shows_only_where_the_repository_holds_some() {
        assertThat(RepoUiState(requested, details = details).tabs).doesNotContain(RepoTab.DISCUSSIONS)
        assertThat(RepoUiState(requested, details = withDiscussions).tabs).contains(RepoTab.DISCUSSIONS)
        assertThat(RepoUiState(requested).tabs).doesNotContain(RepoTab.DISCUSSIONS)
    }

    @Test
    fun discussions_are_listed_and_the_next_page_is_asked_for_after_the_last() = test {
        cache(repo = withDiscussions)
        repos.discussionPages[null] = ForgeResult.Success(DiscussionPage(listOf(discussionSummary(2, "Second"), discussionSummary(1, "First")), next = "c1"))
        repos.discussionPages["c1"] = ForgeResult.Success(DiscussionPage(listOf(discussionSummary(1, "First"), discussionSummary(0, "Zeroth")), next = null))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectTab(RepoTab.DISCUSSIONS)
        advanceUntilIdle()
        assertThat((viewModel.state.value.discussions as Loadable.Loaded).value.map { it.number }).containsExactly(2, 1).inOrder()
        assertThat(viewModel.state.value.discussionPaging.next).isNotNull()

        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()
        // One that moved down a page meanwhile is not listed twice.
        assertThat((viewModel.state.value.discussions as Loadable.Loaded).value.map { it.number }).containsExactly(2, 1, 0).inOrder()
        assertThat(viewModel.state.value.discussionPaging).isEqualTo(ListPaging())
        assertThat(repos.calls.filter { it.startsWith("discussions:") }).containsExactly("discussions:octo/repo", "discussions:octo/repo after c1").inOrder()
    }

    @Test
    fun a_single_page_of_discussions_has_nothing_after_it() = test {
        cache(repo = withDiscussions)
        repos.discussionPages[null] = ForgeResult.Success(DiscussionPage(listOf(discussionSummary(1, "First")), next = null))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectTab(RepoTab.DISCUSSIONS)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(viewModel.state.value.discussionPaging).isEqualTo(ListPaging())
        assertThat(repos.calls.count { it.startsWith("discussions:") }).isEqualTo(1)
    }

    @Test
    fun a_page_of_discussions_that_couldn_t_be_loaded_can_be_asked_for_again() = test {
        cache(repo = withDiscussions)
        repos.discussionPages[null] = ForgeResult.Success(DiscussionPage(listOf(discussionSummary(1, "First")), next = "c1"))
        repos.discussionPages["c1"] = ForgeResult.Failure(ForgeError.Network)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectTab(RepoTab.DISCUSSIONS)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()
        assertThat(viewModel.state.value.discussionPaging.failed).isTrue()

        repos.discussionPages["c1"] = ForgeResult.Success(DiscussionPage(listOf(discussionSummary(0, "Zeroth")), next = null))
        viewModel.loadMore()
        advanceUntilIdle()
        assertThat((viewModel.state.value.discussions as Loadable.Loaded).value).hasSize(2)
    }

    @Test
    fun signed_out_the_discussions_say_they_need_an_account() = test {
        cache(repo = withDiscussions)
        repos.discussionPages[null] = ForgeResult.Failure(ForgeError.Unauthorized)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectTab(RepoTab.DISCUSSIONS)
        advanceUntilIdle()

        assertThat(viewModel.state.value.discussions).isEqualTo(Loadable.Failed(ForgeError.Unauthorized))
    }
}
