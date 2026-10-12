package fr.arthurbrugiere.forgeline.repo.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.MergeStyle
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.PagesBuild
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.PagesSite
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class RepoSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val changes = mutableListOf<RepoSettingsChange>()
    private val pages = mutableListOf<PagesChange>()
    private val live = PagesSite(published = true, url = "https://octo.example/tools/", canPublish = true, build = PagesBuild.Workflow, httpsEnforced = false, customDomain = "")
    private val tools = RepoId("octo", "tools")
    private val gitHub = RepoSettings(
        description = "Tools for octocats", website = "", topics = listOf("kotlin", "tools"), defaultBranch = "main",
        switches = linkedMapOf(
            RepoSwitch.ISSUES to true, RepoSwitch.WIKI to false, RepoSwitch.DISCUSSIONS to false,
            RepoSwitch.MERGE_COMMIT to true, RepoSwitch.SQUASH_MERGE to false, RepoSwitch.DELETE_BRANCH_ON_MERGE to true,
        ),
    )
    private val gitLab = RepoSettings(
        description = "", hasWebsite = false, defaultBranch = "main",
        switches = linkedMapOf(RepoSwitch.PULL_REQUESTS to true, RepoSwitch.CI to true, RepoSwitch.REQUIRE_PIPELINE to false),
        membersOnly = setOf(RepoSwitch.CI), mergeStyle = MergeStyle.MERGE_COMMIT,
    )

    private fun setContent(state: RepoSettingsUiState) {
        composeRule.setContent {
            ForgelineTheme {
                RepoSettingsScreen(
                    state = state,
                    onBack = { events += "back" },
                    onRefresh = { events += "refresh" },
                    onChange = { changes += it },
                    onLoadBranches = { events += "branches" },
                    onOpenUrl = { events += "url:$it" },
                    onChangePages = { pages += it },
                    onRename = { events += "rename:$it" },
                    onDelete = { events += "delete" },
                    onRenamed = { events += "renamed:${it.fullName}" },
                    onDeleted = { events += "deleted" },
                    onRefusalShown = { events += "refusal-shown" },
                    onErrorShown = { events += "error-shown" },
                )
            }
        }
    }

    private fun shown(settings: RepoSettings = gitHub, repo: RepoId = tools) = RepoSettingsUiState(repo, settings)

    private fun reach(text: String) = composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private fun switch(text: String) = composeRule.onNode(hasText(text) and isToggleable())

    @Test
    fun says_what_the_repository_has_set_in_its_groups() {
        setContent(shown())

        composeRule.onNode(hasText("Repository settings") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("octo/tools").assertIsDisplayed()
        composeRule.onNodeWithText("Tools for octocats").assertIsDisplayed()
        composeRule.onNodeWithText("kotlin, tools").assertIsDisplayed()
        // An empty website says so rather than nothing.
        composeRule.onNodeWithText("None").assertIsDisplayed()
        switch("Issues").assertIsOn()
        switch("Wiki").assertIsOff()
        reach("Delete the branch once merged")
        switch("Allow merge commits").assertIsOn()
        switch("Allow squash merging").assertIsOff()
    }

    @Test
    fun a_switch_tells_the_forge_as_soon_as_it_is_flipped() {
        setContent(shown())

        switch("Wiki").performClick()
        switch("Issues").performClick()

        assertThat(changes).containsExactly(RepoSettingsChange.Switch(RepoSwitch.WIKI, true), RepoSettingsChange.Switch(RepoSwitch.ISSUES, false)).inOrder()
    }

    @Test
    fun the_description_is_written_in_a_sheet_and_saved_from_it() {
        setContent(shown())

        composeRule.onNodeWithText("Description").performClick()
        // Nothing to save until something is written.
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("  Faster tools ")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(changes).containsExactly(RepoSettingsChange.Description("Faster tools"))
    }

    @Test
    fun topics_are_added_as_forges_write_them_and_removed_by_a_tap() {
        setContent(shown())

        composeRule.onNodeWithText("Topics").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Jetpack Compose")
        composeRule.onNodeWithText("Add").performClick()
        composeRule.onNodeWithText("jetpack-compose").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove kotlin").performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertThat(changes).containsExactly(RepoSettingsChange.Topics(listOf("tools", "jetpack-compose")))
    }

    @Test
    fun the_default_branch_is_picked_among_the_branches() {
        setContent(shown().copy(branches = Loadable.Loaded(listOf("main", "dev"))))

        composeRule.onNodeWithText("Default branch").performClick()
        composeRule.onNodeWithText("dev").performClick()

        assertThat(events).contains("branches")
        assertThat(changes).containsExactly(RepoSettingsChange.DefaultBranch("dev"))
    }

    @Test
    fun what_the_forge_refuses_of_a_sheet_is_said_in_it() {
        setContent(shown().copy(refused = ForgeError.Network))

        composeRule.onNodeWithText("Description").performClick()

        composeRule.onNode(hasText("The forge refused the change", substring = true)).assertIsDisplayed()
    }

    @Test
    fun gitlab_shows_its_own_settings_in_its_own_words() {
        setContent(shown(gitLab, RepoId("group", "app", ForgeInstance.GitLab)))

        // No website of its own, and one way of merging.
        composeRule.onNodeWithText("Website").assertDoesNotExist()
        switch("Merge requests").assertIsOn()
        switch("CI/CD pipelines").assertIsOn()
        composeRule.onNodeWithText("Members only").assertIsDisplayed()
        reach("Pipelines must succeed")
        composeRule.onNodeWithText("How it merges").performClick()
        composeRule.onNodeWithText("Fast-forward, no merge commit").performClick()

        assertThat(changes).containsExactly(RepoSettingsChange.Style(MergeStyle.FAST_FORWARD))
    }

    @Test
    fun forgejo_proposes_one_way_of_merging_first() {
        setContent(shown(gitHub.copy(defaultMerge = MergeMethod.MERGE), RepoId("forgejo", "tools", ForgeInstance.Codeberg)))

        reach("Proposed first")
        composeRule.onNodeWithText("Proposed first").performClick()
        composeRule.onNodeWithText("Squash and merge").performClick()

        assertThat(changes).containsExactly(RepoSettingsChange.DefaultMerge(MergeMethod.SQUASH))
    }

    @Test
    fun the_rest_is_on_the_forge_s_website() {
        setContent(shown())

        composeRule.onNodeWithContentDescription("Open on GitHub").performClick()
        reach("More settings on GitHub")
        composeRule.onNodeWithText("More settings on GitHub").performClick()

        assertThat(events).containsExactly("url:https://github.com/octo/tools/settings", "url:https://github.com/octo/tools/settings").inOrder()
    }

    @Test
    fun settings_that_cannot_be_read_offer_to_try_again() {
        setContent(RepoSettingsUiState(tools, error = ForgeError.Network))

        composeRule.onNodeWithText("Couldn't load the settings").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun who_sees_it_changes_only_once_picked_and_confirmed() {
        setContent(shown(gitLab.copy(visibility = RepoVisibility.PRIVATE, visibilities = RepoVisibility.entries), RepoId("group", "app", ForgeInstance.GitLab)))

        reach("Danger zone")
        reach("Delete this repository")
        composeRule.onNodeWithText("Visibility").performClick()
        // Nothing to confirm until another is picked.
        composeRule.onNodeWithText("Change visibility").assertIsNotEnabled()
        composeRule.onNodeWithText("Internal").performClick()
        assertThat(changes).isEmpty()
        composeRule.onNodeWithText("Change visibility").performClick()

        assertThat(changes).containsExactly(RepoSettingsChange.Visibility(RepoVisibility.INTERNAL))
    }

    @Test
    fun a_forge_without_internal_repositories_does_not_offer_them() {
        setContent(shown())

        reach("Delete this repository")
        composeRule.onNodeWithText("Visibility").performClick()

        composeRule.onNodeWithText("Private").assertIsDisplayed()
        composeRule.onNodeWithText("Internal").assertDoesNotExist()
    }

    @Test
    fun archiving_asks_first_and_can_be_let_go() {
        setContent(shown())

        reach("Delete this repository")
        composeRule.onNodeWithText("Archive").performClick()
        composeRule.onNodeWithText("Archive this repository?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertThat(changes).isEmpty()
        composeRule.onNodeWithText("Archive").performClick()
        composeRule.onAllNodes(hasText("Archive"))[1].performClick()

        assertThat(changes).containsExactly(RepoSettingsChange.Archived(true))
    }

    @Test
    fun an_archived_repository_says_so_and_offers_to_unarchive() {
        setContent(shown(gitHub.copy(archived = true)))

        reach("Delete this repository")
        composeRule.onNodeWithText("Archived: it is read-only").assertIsDisplayed()
        composeRule.onNodeWithText("Unarchive").performClick()
        composeRule.onAllNodes(hasText("Unarchive"))[1].performClick()

        assertThat(changes).containsExactly(RepoSettingsChange.Archived(false))
    }

    @Test
    fun renaming_is_written_in_a_sheet() {
        setContent(shown())

        reach("Delete this repository")
        composeRule.onNodeWithText("Rename").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("kit")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(events).containsExactly("rename:kit")
    }

    @Test
    fun deleting_wants_the_full_name_written_out() {
        setContent(shown())

        reach("Delete this repository")
        composeRule.onNodeWithText("Delete this repository").performClick()
        composeRule.onNodeWithText("Delete for good").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("tools")
        composeRule.onNodeWithText("Delete for good").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("octo/tools")
        composeRule.onNodeWithText("Delete for good").performClick()

        assertThat(events).containsExactly("delete")
    }

    @Test
    fun a_sign_in_that_may_not_delete_is_told_to_sign_in_again() {
        setContent(shown().copy(refused = ForgeError.Unauthorized))

        // Said at the foot of the screen while no sheet is open: the row is brought clear of it.
        reach("More settings on GitHub")
        composeRule.onNodeWithText("Delete this repository").performClick()

        composeRule.onNode(hasText("Sign in to GitHub again", substring = true)).assertIsDisplayed()
    }

    @Test
    fun what_the_forge_says_of_a_refusal_is_repeated() {
        setContent(shown().copy(refused = ForgeError.Http(422, "name already exists on this account")))

        reach("More settings on GitHub")
        composeRule.onNodeWithText("Rename").performClick()

        composeRule.onNode(hasText("name already exists on this account", substring = true)).assertIsDisplayed()
    }

    @Test
    fun a_deleted_repository_leaves_only_the_word_of_it() {
        setContent(shown().copy(deleted = RepoDeletion.DELETED))

        composeRule.onNodeWithText("octo/tools is deleted").assertIsDisplayed()
        composeRule.onNodeWithText("Description").assertDoesNotExist()
        composeRule.onNodeWithText("Done").performClick()

        assertThat(events).containsExactly("deleted")
    }

    @Test
    fun a_repository_its_forge_keeps_a_while_says_it_can_still_be_restored() {
        setContent(shown(gitLab, RepoId("group", "app", ForgeInstance.GitLab)).copy(deleted = RepoDeletion.SCHEDULED))

        composeRule.onNodeWithText("group/app will be deleted").assertIsDisplayed()
        composeRule.onNode(hasText("it can be restored", substring = true)).assertIsDisplayed()
    }

    @Test
    fun a_renamed_repository_is_opened_where_it_now_is() {
        setContent(shown().copy(renamedTo = RepoId("octo", "kit")))

        composeRule.waitForIdle()
        assertThat(events).containsExactly("renamed:octo/kit")
    }

    @Test
    fun a_repository_that_publishes_nothing_is_offered_to_from_a_workflow_or_a_branch() {
        setContent(shown().copy(pages = PagesSite(published = false, canPublish = true), branches = Loadable.Loaded(listOf("main", "gh-pages"))))

        reach("Publish a website")
        composeRule.onNodeWithText("Not published").assertIsDisplayed()
        composeRule.onNodeWithText("Publish a website").performClick()
        // A workflow unless said otherwise; a branch asks which, and which folder of it.
        composeRule.onNodeWithText("A branch").performClick()
        composeRule.onNodeWithText("gh-pages").performClick()
        composeRule.onNodeWithText("/docs").performClick()
        composeRule.onAllNodes(hasText("Publish a website"))[1].performClick()

        assertThat(events).contains("branches")
        assertThat(pages).containsExactly(PagesChange.Publish(PagesBuild.Branch("gh-pages", "/docs")))
    }

    @Test
    fun a_published_site_says_where_it_is_and_what_can_be_set_of_it() {
        setContent(shown().copy(pages = live))

        reach("Unpublish")
        composeRule.onNodeWithText("https://octo.example/tools/").performClick()
        composeRule.onNodeWithText("GitHub Actions").assertIsDisplayed()
        switch("Enforce HTTPS").assertIsOff()
        switch("Enforce HTTPS").performClick()

        assertThat(events).containsExactly("url:https://octo.example/tools/")
        assertThat(pages).containsExactly(PagesChange.Https(true))
    }

    @Test
    fun the_site_s_source_and_domain_are_changed_from_their_sheets() {
        setContent(shown().copy(pages = live.copy(build = PagesBuild.Branch("main", "/"))))

        reach("Unpublish")
        composeRule.onNodeWithText("main, /").performClick()
        // Nothing to save while it is what the site is built from already.
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithText("GitHub Actions").performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertThat(pages).containsExactly(PagesChange.Publish(PagesBuild.Workflow))
    }

    @Test
    fun a_custom_domain_is_written_in_a_sheet() {
        setContent(shown().copy(pages = live))

        reach("Unpublish")
        composeRule.onNodeWithText("Custom domain").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("tools.example")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(pages).containsExactly(PagesChange.Domain("tools.example"))
    }

    @Test
    fun unpublishing_asks_first() {
        setContent(shown().copy(pages = live))

        reach("Unpublish")
        composeRule.onNodeWithText("Unpublish").performClick()
        composeRule.onNodeWithText("Unpublish the website?").assertIsDisplayed()
        assertThat(pages).isEmpty()
        composeRule.onAllNodes(hasText("Unpublish"))[1].performClick()

        assertThat(pages).containsExactly(PagesChange.Unpublish)
    }

    @Test
    fun a_gitlab_site_shows_once_published_with_only_what_its_api_sets() {
        val project = RepoId("group", "app", ForgeInstance.GitLab)
        setContent(shown(gitLab, project).copy(pages = PagesSite(published = true, url = "https://app.gitlab.io", httpsEnforced = true)))

        reach("Unpublish")
        composeRule.onNodeWithText("https://app.gitlab.io").assertIsDisplayed()
        switch("Enforce HTTPS").assertIsOn()
        composeRule.onNodeWithText("Built from").assertDoesNotExist()
        composeRule.onNodeWithText("Custom domain").assertDoesNotExist()
    }

    @Test
    fun no_website_is_offered_where_nothing_can_be_set_of_one() {
        // GitLab before its CI published one, then a forge without the setting.
        setContent(shown(gitLab, RepoId("group", "app", ForgeInstance.GitLab)).copy(pages = PagesSite(published = false, url = "https://app.gitlab.io", httpsEnforced = true)))

        reach("More settings on GitLab")
        composeRule.onNodeWithText("Pages").assertDoesNotExist()
        composeRule.onNodeWithText("Publish a website").assertDoesNotExist()
    }

    /** On a screen tall enough to hold them all: a row cut by the edge of a shorter one measures as less than it is. */
    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun every_row_is_a_target_of_48dp_at_least() {
        setContent(shown())

        composeRule.onNodeWithText("Delete this repository").assertIsDisplayed()
        composeRule.onNodeWithText("More settings on GitHub").assertIsDisplayed()
        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
