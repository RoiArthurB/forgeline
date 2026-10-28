package fr.arthurbrugiere.forgeline.collections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoCollection
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class CollectionScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val github = Account("github:github.com:me", ForgeInstance.GitHub, ForgeUser("me", null, null))

    private fun setCollections(state: CollectionsUiState) = composeRule.setContent {
        CollectionsScreen(
            state,
            onBack = {},
            onOpenCollection = { events += "open:$it" },
            onCreate = { events += "create:$it" },
            onImport = { events += "import:${it.user.login}" },
            onImportShown = { events += "import-shown" },
        )
    }

    private fun setCollection(state: CollectionUiState) = composeRule.setContent {
        CollectionScreen(
            state,
            onBack = { events += "back" },
            onOpenRepo = { events += "repo:${it.fullName}" },
            onRename = { events += "rename:$it" },
            onRemove = { events += "remove:${it.fullName}" },
            onRestore = { events += "restore:${it.id.fullName}" },
            onDelete = { events += "delete" },
        )
    }

    @Test
    fun collections_open_and_a_new_one_is_named_in_place() {
        setCollections(CollectionsUiState(listOf(RepoCollection("c1", "Matrix tools", 4))))

        composeRule.onNodeWithText("4 repositories").assertIsDisplayed()
        composeRule.onNodeWithText("Matrix tools").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Server stuff")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        assertThat(events).containsExactly("open:c1", "create:Server stuff").inOrder()
    }

    @Test
    fun github_lists_are_offered_for_import_and_what_came_is_said() {
        setCollections(CollectionsUiState(emptyList(), importable = listOf(github), imported = fr.arthurbrugiere.forgeline.core.data.collections.ImportReport(2, 5)))

        composeRule.onNodeWithText("No collection yet").assertIsDisplayed()
        composeRule.onNodeWithText("2 lists imported, 5 repositories added.").assertExists()
        composeRule.onNodeWithText("Import your GitHub lists").performClick()

        assertThat(events).contains("import:me")
    }

    @Test
    fun a_collection_lists_its_repositories_and_editing_takes_one_out() {
        setCollection(CollectionUiState("c1", "Tools", listOf(repoSummary("a/one"), repoSummary("b/two", forge = ForgeInstance.Codeberg))))

        composeRule.onNodeWithText("2 repositories").assertIsDisplayed()
        composeRule.onNodeWithText("one", substring = true).performClick()
        composeRule.onNodeWithContentDescription("Edit the collection").performClick()
        composeRule.onNodeWithContentDescription("Take a/one out of the collection").performClick()

        assertThat(events).containsExactly("repo:a/one", "remove:a/one").inOrder()
    }

    @Test
    fun deleting_asks_first() {
        setCollection(CollectionUiState("c1", "Tools", emptyList()))

        composeRule.onNodeWithText("Nothing in it yet").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Edit the collection").performClick()
        composeRule.onNodeWithText("Delete the collection").performClick()
        assertThat(events).isEmpty()
        composeRule.onNodeWithText("Delete “Tools”?").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun every_target_is_48dp_at_least() {
        setCollections(CollectionsUiState(listOf(RepoCollection("c1", "Matrix tools", 4)), importable = listOf(github)))
        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
