package fr.arthurbrugiere.forgeline.user

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.user.DefaultUserRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeUserApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.userProfile
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeUserApi()
    private val accounts = FakeAccountRepository()
    private val repository = DefaultUserRepository(FakeForgeClients(users = api), accounts)
    private val repo = RepoSummary(RepoId("octocat", "Hello-World"), "Hi", "Kotlin", 10, 2, false, null)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t")

    @Test
    fun an_organisation_s_repositories_are_asked_once_it_is_known_to_be_one() = test {
        // Asked together, the list could go out before the profile said "organisation", and miss the private ones.
        api.users["ACROSS-Lab"] = userProfile("ACROSS-Lab").copy(isOrganization = true)

        UserViewModel(ForgeInstance.GitHub, "ACROSS-Lab", repository)
        advanceUntilIdle()

        assertThat(api.reposAsked).containsExactly("ACROSS-Lab organization")
    }

    @Test
    fun a_profile_with_a_readme_opens_on_it() = test {
        val reposApi = fr.arthurbrugiere.forgeline.core.testing.FakeRepoApi()
        reposApi.readmes[RepoId("octocat", "octocat")] = fr.arthurbrugiere.forgeline.core.model.Readme("README.md", "# Hi")
        api.users["octocat"] = userProfile("octocat")

        val viewModel = UserViewModel(ForgeInstance.GitHub, "octocat", DefaultUserRepository(FakeForgeClients(users = api, repos = reposApi), accounts))
        advanceUntilIdle()

        assertThat(viewModel.state.value.tab).isEqualTo(UserTab.README)
        assertThat(viewModel.state.value.readme?.readme?.markdown).isEqualTo("# Hi")
    }

    @Test
    fun a_readme_found_after_a_tab_was_picked_leaves_that_tab() = test {
        val reposApi = fr.arthurbrugiere.forgeline.core.testing.FakeRepoApi()
        reposApi.readmes[RepoId("octocat", "octocat")] = fr.arthurbrugiere.forgeline.core.model.Readme("README.md", "# Hi")
        api.users["octocat"] = userProfile("octocat")

        val viewModel = UserViewModel(ForgeInstance.GitHub, "octocat", DefaultUserRepository(FakeForgeClients(users = api, repos = reposApi), accounts))
        viewModel.selectTab(UserTab.STARRED)
        advanceUntilIdle()

        assertThat(viewModel.state.value.tab).isEqualTo(UserTab.STARRED)
        assertThat(viewModel.state.value.readme).isNotNull()
    }

    @Test
    fun loads_the_profile_and_repositories() = test {
        api.users["octocat"] = userProfile("octocat")
        api.repos["octocat"] = listOf(repo)

        val viewModel = UserViewModel(ForgeInstance.GitHub, "octocat", repository)
        advanceUntilIdle()

        assertThat(viewModel.state.value.profile).isEqualTo(userProfile("octocat"))
        assertThat(viewModel.state.value.repos).isEqualTo(Loadable.Loaded(listOf(repo)))
        assertThat(viewModel.state.value.starred).isEqualTo(Loadable.Idle)
    }

    @Test
    fun the_starred_tab_loads_when_opened() = test {
        api.users["octocat"] = userProfile("octocat")
        api.starred["octocat"] = listOf(repo)
        val viewModel = UserViewModel(ForgeInstance.GitHub, "octocat", repository)
        advanceUntilIdle()

        viewModel.selectTab(UserTab.STARRED)
        advanceUntilIdle()

        assertThat(viewModel.state.value.starred).isEqualTo(Loadable.Loaded(listOf(repo)))
    }

    @Test
    fun following_is_instant_and_adjusts_the_follower_count() = test {
        signIn()
        api.users["octocat"] = userProfile("octocat")
        val viewModel = UserViewModel(ForgeInstance.GitHub, "octocat", repository)
        advanceUntilIdle()
        assertThat(viewModel.state.value.following).isFalse()

        viewModel.toggleFollow()
        runCurrent()

        assertThat(viewModel.state.value.following).isTrue()
        assertThat(viewModel.state.value.profile?.followers).isEqualTo(11)
        advanceUntilIdle()
        assertThat(api.following).containsExactly("octocat")
    }

    @Test
    fun a_failed_follow_is_rolled_back() = test {
        signIn()
        api.users["octocat"] = userProfile("octocat")
        val viewModel = UserViewModel(ForgeInstance.GitHub, "octocat", repository)
        advanceUntilIdle()
        api.failure = ForgeError.Network

        viewModel.toggleFollow()
        advanceUntilIdle()

        assertThat(viewModel.state.value.following).isFalse()
        assertThat(viewModel.state.value.profile?.followers).isEqualTo(10)
        assertThat(viewModel.state.value.followFailed).isTrue()
    }

    @Test
    fun an_unknown_user_is_an_error() = test {
        val viewModel = UserViewModel(ForgeInstance.GitHub, "nobody", repository)
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Http(404, "Not Found"))
    }
}
