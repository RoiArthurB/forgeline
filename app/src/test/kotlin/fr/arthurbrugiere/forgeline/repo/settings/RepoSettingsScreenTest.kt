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
        composeRule.assertEveryTargetIsAtLeast48dp()
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

        reach("More settings on GitHub")
        composeRule.onNodeWithText("More settings on GitHub").performClick()
        composeRule.onNodeWithContentDescription("Open on GitHub").performClick()

        assertThat(events).containsExactly("url:https://github.com/octo/tools/settings", "url:https://github.com/octo/tools/settings").inOrder()
    }

    @Test
    fun settings_that_cannot_be_read_offer_to_try_again() {
        setContent(RepoSettingsUiState(tools, error = ForgeError.Network))

        composeRule.onNodeWithText("Couldn't load the settings").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }
}
