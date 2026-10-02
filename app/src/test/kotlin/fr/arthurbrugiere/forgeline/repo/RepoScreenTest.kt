package fr.arthurbrugiere.forgeline.repo

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
                onOpenIssue = { events += "issue:$it" },
                onNewIssue = { events += "new-issue" },
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
                nowMillis = 0,
            )
        }
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
}
