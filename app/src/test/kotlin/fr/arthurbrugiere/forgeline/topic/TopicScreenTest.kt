package fr.arthurbrugiere.forgeline.topic

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class TopicScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val forges = listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab)
    private val loaded = TopicUiState(
        "selfhosted",
        repos = listOf(repoSummary("a/homelab"), repoSummary("b/bookstack", forge = ForgeInstance.Codeberg)),
        totalCount = 1_234,
        isLoading = false,
        forges = forges,
    )

    private fun setContent(state: TopicUiState) = composeRule.setContent {
        TopicScreen(
            state,
            onBack = { events += "back" },
            onOpenRepo = { events += "repo:${it.key}" },
            onSelectForge = { events += "forge:${it?.host}" },
            onLoadMore = { events += "more" },
            onRetry = { events += "retry" },
        )
    }

    @Test
    fun a_topic_names_itself_counts_its_repositories_and_opens_one() {
        setContent(loaded)

        composeRule.onNodeWithText("selfhosted").assertIsDisplayed()
        composeRule.onNodeWithText("1.2k repositories").assertIsDisplayed()
        composeRule.onNodeWithText("bookstack", substring = true).performClick()

        assertThat(events).containsExactly("repo:codeberg.org/b/bookstack")
    }

    @Test
    fun nothing_tagged_says_so() {
        setContent(TopicUiState("nothing-here", totalCount = 0, isLoading = false, forges = forges))

        composeRule.onNodeWithText("Nothing tagged yet").assertIsDisplayed()
    }

    @Test
    fun a_failure_offers_to_try_again() {
        setContent(TopicUiState("selfhosted", isLoading = false, error = ForgeError.Network, forges = forges))

        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("retry")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun every_target_is_48dp_at_least() {
        setContent(loaded)
        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
