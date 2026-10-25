package fr.arthurbrugiere.forgeline.wiki

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WikiPage
import fr.arthurbrugiere.forgeline.core.model.WikiPageSummary
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WikiViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repo = RepoId("octo", "repo")
    private val repos = FakeRepoRepository()
    private val home = WikiPage("Home", "Home", "Welcome. See [Install](Install).", "https://github.com/octo/repo/wiki/Home")

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun a_wiki_opens_on_its_first_page_with_the_list_of_the_others() = test {
        repos.wikiPageByKey["Home"] = ForgeResult.Success(home)
        repos.wikiPages = ForgeResult.Success(listOf(WikiPageSummary("Home", "Home"), WikiPageSummary("Install", "Install")))

        val viewModel = WikiViewModel(repo, null, repos)
        assertThat(viewModel.state.value.key).isEqualTo("Home")
        advanceUntilIdle()

        assertThat(viewModel.state.value.page).isEqualTo(home)
        assertThat(viewModel.state.value.pages?.map { it.key }).containsExactly("Home", "Install").inOrder()
        assertThat(viewModel.state.value.isLoading).isFalse()
        assertThat(repos.calls).containsExactly("wikiPage:octo/repo:Home", "wikiPages:octo/repo")
    }

    @Test
    fun a_page_is_opened_by_its_key_and_a_forge_that_can_t_list_pages_lists_none() = test {
        repos.wikiPageByKey["Install"] = ForgeResult.Success(home.copy(key = "Install", title = "Install"))

        val viewModel = WikiViewModel(repo, "Install", repos)
        advanceUntilIdle()

        assertThat(viewModel.state.value.page?.title).isEqualTo("Install")
        assertThat(viewModel.state.value.pages).isNull()
        assertThat(viewModel.state.value.webUrl).isEqualTo("https://github.com/octo/repo/wiki/Home")
    }

    @Test
    fun a_page_that_can_t_be_read_says_why_and_can_be_asked_for_again() = test {
        repos.wikiPageByKey["Home"] = ForgeResult.Failure(ForgeError.Network)
        val viewModel = WikiViewModel(repo, null, repos)
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.webUrl).isEqualTo("https://github.com/octo/repo/wiki/Home")

        repos.wikiPageByKey["Home"] = ForgeResult.Success(home)
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.page).isEqualTo(home)
    }
}
