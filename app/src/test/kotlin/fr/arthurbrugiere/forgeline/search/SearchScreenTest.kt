package fr.arthurbrugiere.forgeline.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SearchScope
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SearchScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun setContent(state: SearchUiState) {
        composeRule.setContent {
            SearchScreen(
                state = state,
                onQueryChange = { events += "query:$it" },
                onSubmit = { events += "submit" },
                onSelectScope = { events += "scope:$it" },
                onLoadMore = { events += "more" },
                onRetry = { events += "retry" },
                onOpenRepo = { events += "repo:${it.fullName}" },
                onOpenIssue = { events += "issue:${it.repo.fullName}#${it.number}" },
                onOpenUser = { events += "user:$it" },
                onBack = { events += "back" },
                nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
            )
        }
    }

    private fun loaded(vararg items: SearchResult, total: Int = items.size, more: Boolean = false, scope: SearchScope = SearchScope.REPOSITORIES) =
        SearchUiState("rocket", scope, ScopeResults("rocket", items.toList(), total, if (more) 2 else null))

    @Test
    fun typing_and_the_keyboard_search_key_submit() {
        setContent(SearchUiState())

        composeRule.onNode(hasSetTextAction()).performTextInput("rocket")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        assertThat(events).containsExactly("query:rocket", "submit").inOrder()
    }

    @Test
    fun before_searching_it_explains_what_can_be_found() {
        setContent(SearchUiState())

        composeRule.onNodeWithText("Find anything on GitHub").assertIsDisplayed()
    }

    @Test
    fun scopes_are_tabs() {
        setContent(loaded(scope = SearchScope.ISSUES))

        composeRule.onNodeWithText("Issues & PRs").assertIsSelected()
        composeRule.onNodeWithText("People").performClick()

        assertThat(events).containsExactly("scope:USERS")
    }

    @Test
    fun results_open_what_they_are() {
        setContent(
            loaded(
                SearchResult.Repository(repoSummary("acme/rocket")),
                SearchResult.Issue(IssueSearchResult(RepoId("acme", "rocket"), issueSummary(42, "Launch fails"))),
                SearchResult.User(UserSummary("octocat", null, isOrganization = false)),
                total = 1_234,
            ),
        )

        composeRule.onNodeWithText("1.2k results").assertIsDisplayed()
        // The repository row; the issue row names the same repo above its title.
        composeRule.onAllNodesWithText("acme/rocket")[0].performClick()
        composeRule.onNodeWithText("Launch fails").performClick()
        composeRule.onNodeWithText("octocat").performClick()

        assertThat(events).containsExactly("repo:acme/rocket", "issue:acme/rocket#42", "user:octocat").inOrder()
    }

    @Test
    fun organizations_are_labelled() {
        setContent(loaded(SearchResult.User(UserSummary("github", null, isOrganization = true)), scope = SearchScope.USERS))

        composeRule.onNodeWithText("Organization").assertIsDisplayed()
    }

    @Test
    fun reaching_the_end_loads_more() {
        setContent(loaded(SearchResult.Repository(repoSummary("acme/rocket")), total = 40, more = true))

        composeRule.waitForIdle()
        assertThat(events).contains("more")
    }

    @Test
    fun no_results_say_so() {
        setContent(loaded())

        composeRule.onNodeWithText("No results").assertIsDisplayed()
    }

    @Test
    fun a_failed_search_offers_a_retry() {
        setContent(SearchUiState("rocket", results = ScopeResults("rocket", error = ForgeError.RateLimited(null))))

        composeRule.onNodeWithText("Retry").performClick()
        assertThat(events).containsExactly("retry")
    }

    @Test
    fun the_query_can_be_cleared_and_back_leaves() {
        setContent(SearchUiState("rocket"))

        composeRule.onNode(hasContentDescription("Clear")).performClick()
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()

        assertThat(events).containsExactly("query:", "back").inOrder()
    }
}
