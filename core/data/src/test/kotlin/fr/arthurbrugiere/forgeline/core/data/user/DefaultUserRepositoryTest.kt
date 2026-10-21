package fr.arthurbrugiere.forgeline.core.data.user

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeUserApi
import fr.arthurbrugiere.forgeline.core.testing.userProfile
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultUserRepositoryTest {
    private val api = FakeUserApi()
    private val accounts = FakeAccountRepository()
    private val repository = DefaultUserRepository(FakeForgeClients(users = api), accounts)

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "ghp_token")

    @Test
    fun profiles_are_remembered_for_an_instant_reopen() = runTest {
        api.users["octocat"] = userProfile("octocat")

        repository.user(ForgeInstance.GitHub, "octocat")

        assertThat(repository.cachedUser(ForgeInstance.GitHub, "octocat")).isEqualTo(userProfile("octocat"))
        assertThat(repository.cachedUser(ForgeInstance.GitHub, "OctoCat")).isEqualTo(userProfile("octocat"))
    }

    @Test
    fun one_s_own_repositories_and_an_organisation_s_are_asked_so_the_private_ones_come_too() = runTest {
        signIn()
        api.users["ACROSS-Lab"] = userProfile("ACROSS-Lab").copy(isOrganization = true)
        repository.user(ForgeInstance.GitHub, "ACROSS-Lab")

        repository.repos(ForgeInstance.GitHub, "Me")
        repository.repos(ForgeInstance.GitHub, "ACROSS-Lab")
        repository.repos(ForgeInstance.GitHub, "octocat")

        assertThat(api.reposAsked).containsExactly("Me mine", "ACROSS-Lab organization", "octocat").inOrder()
    }

    private val repoApi = fr.arthurbrugiere.forgeline.core.testing.FakeRepoApi()
    private val clients = FakeForgeClients(users = api, repos = repoApi).also { all ->
        // One fake answers for every forge here: each forge's keys say where they look.
        all.put(ForgeInstance.Codeberg, FakeForgeClients(users = api, repos = repoApi))
        all.put(ForgeInstance.GitLab, FakeForgeClients(users = api, repos = repoApi))
    }
    private val withRepos = DefaultUserRepository(clients, accounts)

    @Test
    fun a_person_s_readme_is_in_the_repository_named_after_them() = runTest {
        val home = fr.arthurbrugiere.forgeline.core.model.RepoId("octocat", "octocat")
        repoApi.readmes[home] = fr.arthurbrugiere.forgeline.core.model.Readme("README.md", "# Hi, I'm Octocat")

        val found = withRepos.profileReadme(ForgeInstance.GitHub, "octocat", organization = false)

        assertThat(found?.repo).isEqualTo(home)
        assertThat(found?.readme?.markdown).isEqualTo("# Hi, I'm Octocat")
    }

    @Test
    fun an_organisation_shows_its_members_the_private_readme_and_everyone_else_the_public_one() = runTest {
        val public = fr.arthurbrugiere.forgeline.core.model.RepoId("acme", ".github")
        val private = fr.arthurbrugiere.forgeline.core.model.RepoId("acme", ".github-private")
        repoApi.files[public to "profile/README.md"] = "Welcome to Acme"
        repoApi.files[private to "profile/README.md"] = "Hello, team"

        // Signed out: the private one isn't even asked.
        assertThat(withRepos.profileReadme(ForgeInstance.GitHub, "acme", organization = true)?.readme?.markdown).isEqualTo("Welcome to Acme")
        assertThat(repoApi.calls).doesNotContain("file:acme/.github-private:profile/README.md@HEAD")
        signIn()
        assertThat(withRepos.profileReadme(ForgeInstance.GitHub, "acme", organization = true)?.readme?.markdown).isEqualTo("Hello, team")
        // A member of an organisation without a private one gets the public one.
        repoApi.files.remove(private to "profile/README.md")
        assertThat(withRepos.profileReadme(ForgeInstance.GitHub, "acme", organization = true)?.repo).isEqualTo(public)
    }

    @Test
    fun forgejo_and_gitlab_keep_profile_readmes_where_they_say() = runTest {
        val profile = fr.arthurbrugiere.forgeline.core.model.RepoId("ana", ".profile", ForgeInstance.Codeberg)
        val group = fr.arthurbrugiere.forgeline.core.model.RepoId("acme", "gitlab-profile", ForgeInstance.GitLab)
        repoApi.readmes[profile] = fr.arthurbrugiere.forgeline.core.model.Readme("README.md", "Ana on Codeberg")
        repoApi.readmes[group] = fr.arthurbrugiere.forgeline.core.model.Readme("README.md", "Acme on GitLab")

        assertThat(withRepos.profileReadme(ForgeInstance.Codeberg, "ana", organization = false)?.repo).isEqualTo(profile)
        assertThat(withRepos.profileReadme(ForgeInstance.GitLab, "acme", organization = true)?.repo).isEqualTo(group)
        assertThat(withRepos.profileReadme(ForgeInstance.GitHub, "nobody", organization = false)).isNull()
    }

    @Test
    fun signed_out_the_follow_state_is_unknown_and_cannot_change() = runTest {
        assertThat(repository.isFollowing(ForgeInstance.GitHub, "octocat")).isNull()
        assertThat(repository.setFollowing(ForgeInstance.GitHub, "octocat", true)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.tokens).isEmpty()
    }

    @Test
    fun signed_in_following_uses_the_token() = runTest {
        signIn()

        assertThat(repository.isFollowing(ForgeInstance.GitHub, "octocat")).isFalse()
        repository.setFollowing(ForgeInstance.GitHub, "octocat", true)

        assertThat(repository.isFollowing(ForgeInstance.GitHub, "octocat")).isTrue()
        assertThat(api.tokens.distinct()).containsExactly("ghp_token")
    }

    @Test
    fun a_failed_follow_lookup_is_unknown_not_false() = runTest {
        signIn()
        api.failure = ForgeError.Network

        assertThat(repository.isFollowing(ForgeInstance.GitHub, "octocat")).isNull()
    }

    @Test
    fun the_signed_in_user_cannot_follow_themselves() = runTest {
        signIn()

        assertThat(repository.isFollowing(ForgeInstance.GitHub, "me")).isNull()
        assertThat(repository.isFollowing(ForgeInstance.GitHub, "ME")).isNull()
    }

    @Test
    fun the_same_login_on_two_forges_is_two_people() = runTest {
        // Codeberg's alice isn't GitHub's: each forge answers for its own, and the cache keeps them apart.
        val codeberg = FakeUserApi().apply { users["alice"] = userProfile("alice").copy(name = "Alice on Codeberg") }
        val clients = FakeForgeClients(users = api).apply { put(ForgeInstance.Codeberg, FakeForgeClients(users = codeberg)) }
        api.users["alice"] = userProfile("alice").copy(name = "Alice on GitHub")
        val repository = DefaultUserRepository(clients, accounts)

        repository.user(ForgeInstance.GitHub, "alice")
        repository.user(ForgeInstance.Codeberg, "alice")

        assertThat(repository.cachedUser(ForgeInstance.GitHub, "alice")?.name).isEqualTo("Alice on GitHub")
        assertThat(repository.cachedUser(ForgeInstance.Codeberg, "alice")?.name).isEqualTo("Alice on Codeberg")
    }

    @Test
    fun following_uses_the_account_signed_in_on_that_forge() = runTest {
        signIn()
        val codeberg = FakeUserApi()
        val clients = FakeForgeClients(users = api).apply { put(ForgeInstance.Codeberg, FakeForgeClients(users = codeberg)) }
        val repository = DefaultUserRepository(clients, accounts)

        // Signed in on GitHub only: nothing to follow with on Codeberg.
        assertThat(repository.isFollowing(ForgeInstance.Codeberg, "alice")).isNull()
        assertThat(repository.setFollowing(ForgeInstance.Codeberg, "alice", true)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))

        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb_token")
        repository.setFollowing(ForgeInstance.Codeberg, "alice", true)

        assertThat(codeberg.tokens).containsExactly("cb_token")
    }
}
