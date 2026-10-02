package fr.arthurbrugiere.forgeline.actions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.DispatchInputType
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.repo.Loadable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class DispatchSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val releaseWorkflow = Workflow(1, "Release", ".github/workflows/release.yml")
    private val pushWorkflow = Workflow(2, "Continuous Integration", ".github/workflows/ci.yml")
    private val deployWorkflow = Workflow(3, "Deploy", ".github/workflows/deploy.yml")

    private val booleanInput = DispatchInput(
        name = "dry_run",
        description = "Run without publishing artifacts",
        type = DispatchInputType.BOOLEAN,
        required = false,
        default = "false",
        options = emptyList(),
    )

    private val choiceInput = DispatchInput(
        name = "environment",
        description = "Target deployment environment",
        type = DispatchInputType.CHOICE,
        required = true,
        default = "staging",
        options = listOf("staging", "production", "canary"),
    )

    private val textInput = DispatchInput(
        name = "version_tag",
        description = "Version tag to release",
        type = DispatchInputType.STRING,
        required = true,
        default = "e.g. v1.0.0",
        options = emptyList(),
    )

    private val numberInput = DispatchInput(
        name = "retry_count",
        description = "Number of retries",
        type = DispatchInputType.NUMBER,
        required = false,
        default = "3",
        options = emptyList(),
    )

    @Test
    fun loading_workflows_shows_loading_placeholder() {
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(workflows = Loadable.Loading),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("Run a workflow").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading workflows").assertIsDisplayed()
    }

    @Test
    fun failed_to_load_workflows_shows_notice_and_retry_calls_callback() {
        var retried = false
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(workflows = Loadable.Failed(ForgeError.Network)),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = { retried = true },
            )
        }

        composeRule.onNodeWithText("Couldn't load the workflows").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertThat(retried).isTrue()
    }

    @Test
    fun empty_workflows_shows_notice() {
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(workflows = Loadable.Loaded(emptyList())),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("No workflows").assertIsDisplayed()
    }

    @Test
    fun lists_workflows_and_disables_event_only_ones() {
        var selectedWorkflow: Workflow? = null
        val triggers = mapOf(
            1L to Loadable.Loaded(listOf(booleanInput)),
            2L to Loadable.Loaded(null), // null means cannot be dispatched by hand
        )

        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    workflows = Loadable.Loaded(listOf(releaseWorkflow, pushWorkflow)),
                    triggers = triggers,
                ),
                onSelect = { selectedWorkflow = it },
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("Release").assertIsDisplayed()
        composeRule.onNodeWithText("Continuous Integration").assertIsDisplayed()
        composeRule.onNodeWithText("ci.yml · runs on events only").assertIsDisplayed()

        // Clicking event-only workflow should NOT select it
        composeRule.onNodeWithText("Continuous Integration").performClick()
        assertThat(selectedWorkflow).isNull()

        // Clicking startable workflow should select it
        composeRule.onNodeWithText("Release").performClick()
        assertThat(selectedWorkflow).isEqualTo(releaseWorkflow)
    }

    @Test
    fun back_button_clears_selection() {
        var backClicked = false
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    selected = releaseWorkflow,
                    inputs = Loadable.Loaded(emptyList()),
                ),
                onSelect = { if (it == null) backClicked = true },
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithContentDescription("Back to the workflows").performClick()
        assertThat(backClicked).isTrue()
    }

    @Test
    fun ref_field_changes_and_displays_branch() {
        var changedRef = ""
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    selected = releaseWorkflow,
                    ref = "main",
                    inputs = Loadable.Loaded(emptyList()),
                ),
                onSelect = {},
                onRefChange = { changedRef = it },
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("Branch or tag").assertIsDisplayed()
        val refNode = composeRule.onNodeWithText("main")
        refNode.assertIsDisplayed()
        refNode.performTextInput("2")
        assertThat(changedRef).isNotEmpty()
    }

    @Test
    fun renders_boolean_input_and_clicking_yes_sets_true() {
        var changedName = ""
        var changedValue = ""

        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    selected = releaseWorkflow,
                    inputs = Loadable.Loaded(listOf(booleanInput)),
                    values = mapOf("dry_run" to "false"),
                ),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { name, value ->
                    changedName = name
                    changedValue = value
                },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("dry_run").assertIsDisplayed()
        composeRule.onNodeWithText("Run without publishing artifacts").assertIsDisplayed()
        composeRule.onNodeWithText("Yes").performClick()

        assertThat(changedName).isEqualTo("dry_run")
        assertThat(changedValue).isEqualTo("true")
    }

    @Test
    fun renders_choice_chips_and_clicking_option_sets_value() {
        var changedName = ""
        var changedValue = ""

        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    selected = deployWorkflow,
                    inputs = Loadable.Loaded(listOf(choiceInput)),
                    values = mapOf("environment" to "staging"),
                ),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { name, value ->
                    changedName = name
                    changedValue = value
                },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("environment").assertIsDisplayed()
        composeRule.onNodeWithText("staging").assertIsDisplayed()
        composeRule.onNodeWithText("production").assertIsDisplayed()
        composeRule.onNodeWithText("canary").assertIsDisplayed()

        composeRule.onNodeWithText("production").performClick()
        assertThat(changedName).isEqualTo("environment")
        assertThat(changedValue).isEqualTo("production")
    }

    @Test
    fun renders_text_inputs_with_required_badge() {
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    selected = releaseWorkflow,
                    inputs = Loadable.Loaded(listOf(textInput, numberInput)),
                    values = emptyMap(),
                ),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("version_tag").assertIsDisplayed()
        composeRule.onNodeWithText("Required").assertIsDisplayed()
        composeRule.onNodeWithText("retry_count").assertIsDisplayed()
    }

    @Test
    fun missing_required_fields_displays_warning_and_hides_start_button() {
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    selected = releaseWorkflow,
                    ref = "main",
                    inputs = Loadable.Loaded(listOf(textInput)),
                    values = emptyMap(), // version_tag is required but empty
                ),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("Fill in version_tag to run it.").assertIsDisplayed()
        composeRule.onNode(hasText("Run workflow")).assertDoesNotExist()
    }

    @Test
    fun filled_required_fields_enables_start_button_and_clicking_calls_onStart() {
        var startCalled = false
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    selected = releaseWorkflow,
                    ref = "main",
                    inputs = Loadable.Loaded(listOf(textInput)),
                    values = mapOf("version_tag" to "v1.0.0"),
                ),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = { startCalled = true },
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("Run workflow").performClick()
        assertThat(startCalled).isTrue()
    }

    @Test
    fun sending_state_displays_asking_forge_button() {
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    selected = releaseWorkflow,
                    ref = "main",
                    inputs = Loadable.Loaded(listOf(textInput)),
                    values = mapOf("version_tag" to "v1.0.0"),
                    sending = true,
                ),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNode(hasText("Asking", substring = true)).assertIsDisplayed()
    }

    @Test
    fun forbidden_403_error_displays_write_access_notice() {
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    selected = releaseWorkflow,
                    ref = "main",
                    inputs = Loadable.Loaded(emptyList()),
                    sendError = ForgeError.Http(403, null),
                ),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNode(hasText("write access", substring = true)).assertIsDisplayed()
    }

    @Test
    fun generic_error_displays_error_message() {
        composeRule.setContent {
            DispatchContent(
                state = DispatchUiState(
                    selected = releaseWorkflow,
                    ref = "main",
                    inputs = Loadable.Loaded(emptyList()),
                    sendError = ForgeError.Network,
                ),
                onSelect = {},
                onRefChange = {},
                onRefDone = {},
                onValueChange = { _, _ -> },
                onStart = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("Check your connection and try again.").assertIsDisplayed()
    }
}
