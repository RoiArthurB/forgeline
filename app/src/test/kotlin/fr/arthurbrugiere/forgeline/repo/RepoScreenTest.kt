package fr.arthurbrugiere.forgeline.repo

import fr.arthurbrugiere.forgeline.core.testing.discussionSummary
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithContentDescription
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.core.testing.repoDetails
import fr.arthurbrugiere.forgeline.core.testing.workflowRun
import org.junit.Rule
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onParent
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import androidx.compose.ui.test.assertIsSelected
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.ReleaseAsset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class RepoScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    /** For each conversation opened, whether the row said it was a pull request. */
    private val openedAsPullRequest = mutableListOf<Boolean>()
    private val id = RepoId("octo", "repo")
    private val context = ReadmeContext("https://raw.example/octo/repo/main/", "https://blob.example/octo/repo/main/")
    private val loaded = RepoUiState(
        requested = id,
        details = repoDetails("octo/repo", stars = 85_955),
        readme = Readme("README.md", "# Octo Repo\n\nRead the [guide](docs/GUIDE.md)."),
        readmeContext = context,
    )

    private fun setContent(state: RepoUiState, signedIn: Boolean = true) {
        composeRule.setContent {
            RepoScreen(
                state = state,
                signedIn = signedIn,
                onBack = { events += "back" },
                onRefresh = { events += "refresh" },
                onSelectTab = { events += "tab:$it" },
                onRetryTab = { events += "retry" },
                onToggleStar = { events += "star" },
                onOpenDirectory = { events += "dir:$it" },
                onOpenParentDirectory = { events += "up" },
                onOpenFile = { events += "file:${it.path}" },
                onOpenIssue = { number, isPullRequest ->
                    events += "issue:$number"
                    openedAsPullRequest += isPullRequest
                },
                onNewIssue = { events += "new-issue" },
                onShowOpen = { events += "open:$it" },
                onSearch = { events += "search:$it" },
                onOpenRelease = { events += "release:$it" },
                onOpenUser = { events += "user:$it" },
                onLinkClick = { events += "link:$it" },
                onOpenRun = { events += "run:$it" },
                onOpenInBrowser = { events += "browser:$it" },
                onLoadRefs = { events += "refs" },
                onSelectRef = { events += "ref:$it" },
                onRunWorkflow = if (signedIn) ({ events += "run-workflow" }) else null,
                onWorkflowStartShown = { events += "started-shown" },
                onErrorShown = {},
                onStarFailureShown = {},
                onToggleWatch = { events += "watch" },
                onOpenDiscussion = { events += "discussion:$it" },
                onFork = { events += "fork" },
                onWatchFailureShown = { events += "watch-failure-shown" },
                onForkErrorShown = { events += "fork-error-shown" },
                nowMillis = 0,
            )
        }
    }

    @Test
    fun shares_the_address_of_the_repository() {
        setContent(loaded)

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performClick()

        assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()).isEqualTo("https://github.com/octo/repo" to "octo/repo")
    }

    @Test
    fun shows_the_repo_header() {
        setContent(loaded)

        composeRule.onNodeWithText("About octo/repo").assertIsDisplayed()
        composeRule.onNodeWithText("85.9k stars").assertIsDisplayed()
        composeRule.onNodeWithText("MIT").assertIsDisplayed()
        composeRule.onNodeWithText("kotlin").assertIsDisplayed()
    }

    @Test
    fun renders_the_readme_and_resolves_its_relative_links() {
        setContent(loaded)

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Octo Repo")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("guide", substring = true).performClick()

        assertThat(events).contains("link:https://blob.example/octo/repo/main/docs/GUIDE.md")
    }

    @Test
    fun a_repo_without_readme_says_so() {
        setContent(loaded.copy(readme = null))

        composeRule.onNodeWithText("This repository has no README.").assertIsDisplayed()
    }

    @Test
    fun tabs_report_their_selection() {
        setContent(loaded)

        composeRule.onNodeWithText("Issues").performClick()

        assertThat(events).containsExactly("tab:ISSUES")
    }

    @Test
    fun an_issue_can_be_opened_from_the_issues_tab_even_when_it_is_empty() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = Loadable.Loaded(emptyList())))

        composeRule.onNodeWithText("No open issues.").assertIsDisplayed()
        composeRule.onNodeWithText("New issue").performClick()

        assertThat(events).containsExactly("new-issue")
    }

    @Test
    fun a_repository_that_takes_no_issues_offers_none() {
        val issues = Loadable.Loaded(listOf(issueSummary(14127, "Heartbeat recovery escalates")))
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = issues, details = loaded.details?.copy(isArchived = true)))
        composeRule.onNodeWithText("Heartbeat recovery escalates").assertIsDisplayed()
        composeRule.onNodeWithText("New issue").assertDoesNotExist()
    }

    @Test
    fun a_repository_with_its_issues_switched_off_offers_none() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = Loadable.Loaded(emptyList()), details = loaded.details?.copy(hasIssues = false)))

        composeRule.onNodeWithText("New issue").assertDoesNotExist()
    }

    private val someIssues = Loadable.Loaded(listOf(issueSummary(14127, "Heartbeat recovery escalates")))

    @Test
    fun the_issues_tab_switches_between_open_and_closed() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = someIssues))
        composeRule.onNodeWithText("Open").assertIsSelected()

        composeRule.onNodeWithText("Closed").performClick()

        assertThat(events).containsExactly("open:false")
    }

    @Test
    fun the_closed_list_says_so_when_it_is_empty() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = Loadable.Loaded(emptyList()), issueQuery = IssueQuery(open = false)))

        composeRule.onNodeWithText("Closed").assertIsSelected()
        composeRule.onNodeWithText("No closed issues.").assertIsDisplayed()
    }

    @Test
    fun words_are_looked_for_among_the_issues_and_cleared_in_one_press() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = someIssues))
        composeRule.onNodeWithText("Search issues").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Clear").assertDoesNotExist()

        composeRule.onNode(hasSetTextAction()).performTextInput("crash")
        assertThat(events).containsExactly("search:crash")
    }

    @Test
    fun a_search_can_be_cleared_and_says_when_nothing_matches() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = Loadable.Loaded(emptyList()), issueQuery = IssueQuery(text = "crash")))

        composeRule.onNodeWithText("Nothing matches these words.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Clear").performClick()

        assertThat(events).containsExactly("search:")
    }

    @Test
    fun pinned_issues_head_the_open_list_under_their_own_title() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = someIssues, pinned = listOf(issueSummary(2, "Read before reporting"))))

        composeRule.onNode(hasText("Pinned") and isHeading()).assertIsDisplayed()
        composeRule.onNode(hasText("Open issues") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Read before reporting").performClick()

        assertThat(events).containsExactly("issue:2")
    }

    @Test
    fun a_pinned_issue_that_is_also_in_the_list_shows_in_both_places() {
        // The forge lists it among the open ones too: two rows, and no clash between them.
        val pinned = issueSummary(14127, "Heartbeat recovery escalates")
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = someIssues, pinned = listOf(pinned)))

        assertThat(composeRule.onAllNodes(hasText("Heartbeat recovery escalates")).fetchSemanticsNodes()).hasSize(2)
    }

    @Test
    fun pinned_issues_stay_out_of_the_closed_list_and_of_search_results() {
        val pinned = listOf(issueSummary(2, "Read before reporting"))
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = someIssues, pinned = pinned, issueQuery = IssueQuery(open = false)))
        composeRule.onNodeWithText("Read before reporting").assertDoesNotExist()
        composeRule.onNodeWithText("Pinned").assertDoesNotExist()
    }

    @Test
    fun a_search_shows_no_pinned_issue() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = someIssues, pinned = listOf(issueSummary(2, "Read before reporting")), issueQuery = IssueQuery(text = "heart")))

        composeRule.onNodeWithText("Read before reporting").assertDoesNotExist()
    }

    @Test
    fun without_pinned_issues_the_list_needs_no_title() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = someIssues))

        composeRule.onNodeWithText("Pinned").assertDoesNotExist()
        composeRule.onNodeWithText("Open issues").assertDoesNotExist()
    }

    @Test
    fun pull_requests_are_filtered_and_searched_the_same_way_without_a_new_issue_action() {
        setContent(loaded.copy(tab = RepoTab.PULLS, pulls = Loadable.Loaded(emptyList()), pullQuery = IssueQuery(open = false)))

        composeRule.onNodeWithText("No closed pull requests.").assertIsDisplayed()
        composeRule.onNodeWithText("New issue").assertDoesNotExist()
        composeRule.onNodeWithText("Open").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("fix")

        assertThat(events).containsExactly("open:true", "search:fix").inOrder()
    }

    @Test
    fun an_archived_repository_still_filters_and_searches_its_issues() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = someIssues, details = loaded.details?.copy(isArchived = true)))

        composeRule.onNodeWithText("Closed").assertIsDisplayed()
        composeRule.onNodeWithText("Search issues").assertIsDisplayed()
        composeRule.onNodeWithText("New issue").assertDoesNotExist()
    }

    @Test
    fun the_list_controls_are_large_enough_to_press() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = someIssues, issueQuery = IssueQuery(text = "crash")))

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun lists_issues_with_their_labels() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = Loadable.Loaded(listOf(issueSummary(14127, "Heartbeat recovery escalates")))))

        composeRule.onNodeWithText("Heartbeat recovery escalates").assertIsDisplayed()
        composeRule.onNodeWithText("bug").assertIsDisplayed()
        composeRule.onNodeWithText("#14127 opened", substring = true).assertIsDisplayed()
    }

    @Test
    fun issues_and_the_owner_open_their_screens() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = Loadable.Loaded(listOf(issueSummary(14127, "Heartbeat recovery escalates")))))

        composeRule.onNodeWithText("Heartbeat recovery escalates").performClick()
        composeRule.onNodeWithText("octo").performClick()

        assertThat(events).containsExactly("issue:14127", "user:octo").inOrder()
    }

    @Test
    fun a_failed_tab_can_be_retried() {
        setContent(loaded.copy(tab = RepoTab.RELEASES, releases = Loadable.Failed(ForgeError.Network)))

        composeRule.onNodeWithText("Couldn't load this tab.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("retry")
    }

    @Test
    fun empty_lists_say_so() {
        setContent(loaded.copy(tab = RepoTab.PULLS, pulls = Loadable.Loaded(emptyList())))

        composeRule.onNodeWithText("No open pull requests.").assertIsDisplayed()
    }

    @Test
    fun the_code_tab_opens_folders_and_files() {
        val entries = listOf(
            RepoFile("src/app", "app", RepoFileType.DIR, 0),
            RepoFile("src/Main.kt", "Main.kt", RepoFileType.FILE, 10),
        )
        setContent(loaded.copy(tab = RepoTab.CODE, code = CodeState("src", Loadable.Loaded(entries))))

        composeRule.onNodeWithText("app").performClick()
        composeRule.onNodeWithText("Main.kt").performClick()
        composeRule.onNodeWithText("Parent folder").performClick()

        assertThat(events).containsExactly("dir:src/app", "file:src/Main.kt", "up").inOrder()
    }

    @Test
    fun the_readme_and_code_name_the_branch_they_show() {
        setContent(loaded)
        composeRule.onNode(hasContentDescription("Browsing main. Switch branch or tag")).assertIsDisplayed()
    }

    @Test
    fun another_branch_or_tag_is_picked_from_a_sheet() {
        val refs = GitRefs(branches = listOf("dev", "main"), tags = listOf("v2.0", "v1.0"))
        setContent(loaded.copy(refs = Loadable.Loaded(refs)))

        composeRule.onNode(hasContentDescription("Browsing main. Switch branch or tag")).performClick()
        composeRule.onNodeWithText("dev").assertIsDisplayed()
        composeRule.onNodeWithText("Default").assertIsDisplayed()
        composeRule.onNodeWithText("Tags").performClick()
        composeRule.onNodeWithText("v1.0").performClick()

        assertThat(events).containsExactly("refs", "ref:v1.0").inOrder()
    }

    @Test
    fun the_ref_sheet_filters_by_name() {
        val refs = GitRefs(branches = listOf("dev", "feature/login", "main"), tags = emptyList())
        setContent(loaded.copy(refs = Loadable.Loaded(refs)))
        composeRule.onNode(hasContentDescription("Browsing main. Switch branch or tag")).performClick()

        composeRule.onNode(hasSetTextAction()).performTextInput("LOG")

        composeRule.onNodeWithText("feature/login").assertIsDisplayed()
        composeRule.onNodeWithText("dev").assertDoesNotExist()
    }

    @Test
    fun a_readme_on_another_branch_shows_while_it_loads_and_can_be_retried() {
        setContent(loaded.copy(ref = "dev", readme = null, refReadme = Loadable.Failed(ForgeError.Network)))

        composeRule.onNode(hasContentDescription("Browsing dev. Switch branch or tag")).assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't load this tab.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("retry")
    }

    @Test
    fun a_workflow_run_opens() {
        setContent(loaded.copy(tab = RepoTab.ACTIONS, runs = Loadable.Loaded(listOf(workflowRun(7, title = "Fix the build")))))

        composeRule.onNodeWithText("Fix the build").performClick()

        assertThat(events).containsExactly("run:7")
    }

    @Test
    fun signed_in_the_actions_tab_offers_to_run_a_workflow() {
        setContent(loaded.copy(tab = RepoTab.ACTIONS, runs = Loadable.Loaded(emptyList())))

        composeRule.onNodeWithText("Run a workflow").performClick()

        assertThat(events).containsExactly("run-workflow")
    }

    @Test
    fun signed_out_the_actions_tab_only_lists_runs() {
        setContent(loaded.copy(tab = RepoTab.ACTIONS, runs = Loadable.Loaded(emptyList())), signedIn = false)

        composeRule.onNodeWithText("Run a workflow").assertDoesNotExist()
    }

    @Test
    fun star_and_open_on_github() {
        setContent(loaded.copy(starred = true))

        composeRule.onNodeWithText("Starred").performClick()
        composeRule.onNode(hasContentDescription("Open on GitHub")).performClick()

        assertThat(events).containsExactly("star", "browser:https://github.com/octo/repo").inOrder()
    }

    @Test
    fun a_missing_repo_explains_itself() {
        setContent(RepoUiState(requested = id, error = ForgeError.Http(404, "Not Found")))

        composeRule.onNodeWithText("Couldn't open this repository").assertIsDisplayed()
        composeRule.onNodeWithText("It doesn't exist, or it's private.").assertIsDisplayed()
    }

    @Test
    fun the_header_says_which_forge_the_repository_lives_on() {
        setContent(loaded)

        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
    }

    @Test
    fun a_self_hosted_repository_names_its_server() {
        val selfHosted = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
        val details = repoDetails("octo/repo").let { it.copy(id = it.id.copy(forge = selfHosted)) }
        setContent(loaded.copy(requested = details.id, details = details))

        composeRule.onNodeWithText("git.example.org").assertIsDisplayed()
    }

    @Test
    fun the_header_meets_touch_targets_and_names_its_hero() {
        val withSite = loaded.copy(details = loaded.details!!.copy(homepage = "https://octo.example"))
        setContent(withSite)

        composeRule.assertEveryTargetIsAtLeast48dp()
        composeRule.onNode(hasText("repo") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun the_star_button_says_whether_it_is_on() {
        setContent(loaded.copy(starred = true))
        composeRule.onNodeWithText("Starred", useUnmergedTree = true).onParent().assertIsOn()
    }

    private val releases = listOf(
        Release(
            "v2.0.0", "Tools 2.0", "Faster.", java.time.Instant.ofEpochMilli(0).minusSeconds(3 * 86_400L), false, null,
            assets = listOf(ReleaseAsset("tools.apk", 100, 1, "https://example.org/tools.apk"), ReleaseAsset("sums.txt", 10, 1, "https://example.org/sums.txt")),
            isLatest = true,
        ),
        Release("v2.1.0-rc.1", null, null, null, true, null),
    )

    @Test
    fun a_release_row_says_its_tag_age_and_files_and_opens_its_page() {
        setContent(loaded.copy(tab = RepoTab.RELEASES, releases = Loadable.Loaded(releases)))

        // Under the fold of this short screen: there, not necessarily in view.
        composeRule.onNode(hasText("v2.0.0 · ", substring = true) and hasText(" · 2 files", substring = true)).assertExists()
        composeRule.onNodeWithText("Latest").assertExists()
        composeRule.onNodeWithText("Tools 2.0").performClick()

        assertThat(events).containsExactly("release:v2.0.0")
    }

    @Test
    fun a_pre_release_without_a_name_goes_by_its_tag_and_says_what_it_is() {
        setContent(loaded.copy(tab = RepoTab.RELEASES, releases = Loadable.Loaded(releases)))

        composeRule.onNodeWithText("Pre-release").assertExists()
        composeRule.onNodeWithText("v2.1.0-rc.1").performClick()

        assertThat(events).containsExactly("release:v2.1.0-rc.1")
    }

    @Test
    fun a_row_opens_its_conversation_as_the_kind_it_is() {
        // Regression: only the number was passed on. On GitLab !7 and #7 are two conversations, and the issue opened.
        setContent(loaded.copy(tab = RepoTab.PULLS, pulls = Loadable.Loaded(listOf(issueSummary(7, "Keep install flags on retry", isPullRequest = true)))))

        composeRule.onNodeWithText("Keep install flags on retry").performClick()

        assertThat(events).containsExactly("issue:7")
        assertThat(openedAsPullRequest).containsExactly(true)
    }

    @Test
    fun an_issue_row_opens_as_an_issue() {
        setContent(loaded.copy(tab = RepoTab.ISSUES, issues = Loadable.Loaded(listOf(issueSummary(7, "Launch fails on cold start")))))

        composeRule.onNodeWithText("Launch fails on cold start").performClick()

        assertThat(openedAsPullRequest).containsExactly(false)
    }

    @Test
    fun a_repository_can_be_watched_and_no_longer_watched() {
        setContent(loaded.copy(watching = false))
        composeRule.onNodeWithText("Watch", useUnmergedTree = true).onParent().assertIsOff()
        composeRule.onNodeWithText("Watch").performClick()

        assertThat(events).containsExactly("watch")
    }

    @Test
    fun a_watched_repository_says_so() {
        setContent(loaded.copy(watching = true))

        composeRule.onNodeWithText("Watching", useUnmergedTree = true).onParent().assertIsOn()
    }

    @Test
    fun watching_is_not_offered_signed_out_or_before_the_forge_has_said() {
        setContent(loaded.copy(watching = null))
        composeRule.onNodeWithText("Watch").assertDoesNotExist()
        composeRule.onNodeWithText("Watching").assertDoesNotExist()
    }

    @Test
    fun signed_out_only_the_star_is_offered() {
        setContent(loaded.copy(watching = false), signedIn = false)

        composeRule.onNodeWithText("Star").assertIsDisplayed()
        composeRule.onNodeWithText("Watch").assertDoesNotExist()
        composeRule.onNodeWithText("Fork").assertDoesNotExist()
    }

    @Test
    fun a_fork_is_asked_about_first() {
        setContent(loaded)

        composeRule.onNodeWithText("Fork").performClick()
        composeRule.onNodeWithText("Fork repo?").assertIsDisplayed()
        composeRule.onNodeWithText("A copy of this repository will be made in your GitHub account.").assertIsDisplayed()
        assertThat(events).isEmpty()

        composeRule.onAllNodesWithText("Fork")[1].performClick()
        assertThat(events).containsExactly("fork")
        composeRule.onNodeWithText("Fork repo?").assertDoesNotExist()
    }

    @Test
    fun saying_no_forks_nothing() {
        setContent(loaded)

        composeRule.onNodeWithText("Fork").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertThat(events).isEmpty()
        composeRule.onNodeWithText("Fork repo?").assertDoesNotExist()
    }

    @Test
    fun a_fork_on_its_way_says_so_and_can_t_be_asked_again() {
        setContent(loaded.copy(isForking = true))

        composeRule.onNodeWithText("Forking").assertIsNotEnabled()
    }

    @Test
    fun each_reason_a_fork_wasn_t_made_has_its_own_words() {
        val shown = androidx.compose.runtime.mutableStateOf(loaded)
        composeRule.setContent {
            RepoScreen(
                state = shown.value, signedIn = true, onBack = {}, onRefresh = {}, onSelectTab = {}, onRetryTab = {}, onToggleStar = {},
                onOpenDirectory = {}, onOpenParentDirectory = {}, onOpenFile = {}, onOpenIssue = { _, _ -> }, onNewIssue = {}, onOpenUser = {},
                onLinkClick = {}, onOpenRun = {}, onOpenInBrowser = {}, onLoadRefs = {}, onSelectRef = {}, onRunWorkflow = {},
                onWorkflowStartShown = {}, onErrorShown = {}, onStarFailureShown = {}, nowMillis = 0,
                onForkErrorShown = { events += "fork-error-shown"; shown.value = shown.value.copy(forkError = null) },
            )
        }
        shown.value = loaded.copy(forkError = ForgeError.Http(409, null))
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Not forked: your account already has a repository by that name.").fetchSemanticsNodes().isNotEmpty() }

        assertThat(events).containsExactly("fork-error-shown")
    }

    @Test
    fun a_watch_that_failed_is_said_once() {
        setContent(loaded.copy(watching = false, watchFailed = true))

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Couldn't change what you watch. Try again.").fetchSemanticsNodes().isNotEmpty() }
        assertThat(events).containsExactly("watch-failure-shown")
    }

    @Test
    fun the_header_s_actions_are_large_enough_to_tap_even_at_large_text() {
        setContent(loaded.copy(watching = true))

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    private fun withMore(paging: ListPaging) {
        val shown = androidx.compose.runtime.mutableStateOf(loaded.copy(tab = RepoTab.ISSUES, issues = someIssues, issuePaging = paging))
        composeRule.setContent {
            RepoScreen(
                state = shown.value, signedIn = true, onBack = {}, onRefresh = {}, onSelectTab = {}, onRetryTab = {}, onToggleStar = {},
                onOpenDirectory = {}, onOpenParentDirectory = {}, onOpenFile = {}, onOpenIssue = { _, _ -> }, onNewIssue = {}, onOpenUser = {},
                onLinkClick = {}, onOpenRun = {}, onOpenInBrowser = {}, onLoadRefs = {}, onSelectRef = {}, onRunWorkflow = {},
                onWorkflowStartShown = {}, onErrorShown = {}, onStarFailureShown = {}, nowMillis = 0,
                onLoadMore = { events += "more" },
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun reaching_the_end_of_a_list_that_has_more_asks_for_the_next_page_once() {
        withMore(ListPaging(next = 2))

        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasContentDescription("Loading more"))
        composeRule.waitForIdle()

        assertThat(events).containsExactly("more")
    }

    @Test
    fun a_list_that_has_no_more_ends_with_its_last_row() {
        withMore(ListPaging())

        composeRule.onNode(hasContentDescription("Loading more")).assertDoesNotExist()
        assertThat(events).isEmpty()
    }

    @Test
    fun a_page_that_couldn_t_be_loaded_is_asked_for_by_hand() {
        withMore(ListPaging(next = 2, failed = true))

        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Load more"))
        composeRule.waitForIdle()
        // A forge that is down isn't asked again by the list itself.
        assertThat(events).isEmpty()

        composeRule.onNodeWithText("Load more").performClick()
        assertThat(events).containsExactly("more")
    }

    private val withDiscussions = loaded.copy(details = loaded.details!!.copy(hasDiscussions = true), tab = RepoTab.DISCUSSIONS)

    @Test
    fun the_discussions_tab_is_offered_only_where_there_are_some() {
        setContent(loaded)
        composeRule.onNodeWithText("Discussions").assertDoesNotExist()
    }

    @Test
    fun discussions_say_where_they_were_filed_whether_they_were_answered_and_open() {
        setContent(
            withDiscussions.copy(
                discussions = Loadable.Loaded(
                    listOf(
                        discussionSummary(7, "How do I page?", category = "Q&A", comments = 3, isAnswered = true),
                        discussionSummary(8, "Version 3 is out", category = "Announcements", comments = 1, isAnswered = null),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Discussions").assertIsDisplayed()
        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Version 3 is out"))
        composeRule.onNodeWithText("Q&A · Answered · alice", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("3 comments", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("1 comment", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("How do I page?").performClick()

        assertThat(events).containsExactly("discussion:7")
    }

    @Test
    fun a_repository_without_discussions_yet_says_so() {
        setContent(withDiscussions.copy(discussions = Loadable.Loaded(emptyList())))

        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("No discussions yet."))
        composeRule.onNodeWithText("No discussions yet.").assertIsDisplayed()
    }

    @Test
    fun signed_out_the_discussions_say_they_need_an_account_and_offer_the_forge() {
        setContent(withDiscussions.copy(discussions = Loadable.Failed(ForgeError.Unauthorized)), signedIn = false)

        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Sign in to read discussions"))
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("browser:https://github.com/octo/repo/discussions")
    }

    @Test
    fun a_repository_with_a_wiki_offers_it_and_opens_it_on_its_forge() {
        setContent(loaded.copy(details = loaded.details!!.copy(hasWiki = true)), signedIn = false)

        composeRule.onNodeWithText("Wiki").performClick()

        assertThat(events).containsExactly("browser:https://github.com/octo/repo/wiki")
    }

    @Test
    fun a_repository_without_a_wiki_does_not_offer_one() {
        setContent(loaded)

        composeRule.onNodeWithText("Wiki").assertDoesNotExist()
    }

    @Test
    fun a_wiki_is_where_each_forge_keeps_it() {
        assertThat(RepoId("octo", "repo").wikiUrl).isEqualTo("https://github.com/octo/repo/wiki")
        assertThat(RepoId("forgejo", "forgejo", ForgeInstance.Codeberg).wikiUrl).isEqualTo("https://codeberg.org/forgejo/forgejo/wiki")
        assertThat(RepoId("gitlab-org", "gitlab", ForgeInstance.GitLab).wikiUrl).isEqualTo("https://gitlab.com/gitlab-org/gitlab/-/wikis")
    }
}
