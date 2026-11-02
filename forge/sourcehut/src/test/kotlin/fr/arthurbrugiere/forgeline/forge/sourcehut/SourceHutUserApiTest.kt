package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceHutUserApiTest {
    private fun <T> ForgeResult<T>.value(): T = if (this is ForgeResult.Success) value else error("$this")

    private val repositories: (FakeSourceHut.Request) -> String = { request ->
        if (request.string("cursor") == null) {
            """{"data":{"user":{"repositories":{"results":[{"name":"scdoc","description":"man pages","visibility":"PUBLIC","updated":"2026-10-01T00:00:00Z"}],"cursor":"next"}}}}"""
        } else {
            """{"data":{"user":{"repositories":{"results":[{"name":"secret","description":null,"visibility":"PRIVATE","updated":"2026-09-01T00:00:00Z"}],"cursor":null}}}}"""
        }
    }

    @Test
    fun a_profile_reads_with_its_repositories_counted() = runTest {
        val fake = FakeSourceHut(
            listOf(
                "repositories" to repositories,
                "bio" to { _ -> """{"data":{"user":{"canonicalName":"~sircmpwn","username":"sircmpwn","url":"https://drewdevault.com","location":"Amsterdam","bio":"Hacker","created":"2018-01-01T00:00:00Z"}}}""" },
            ),
        )

        val profile = SourceHutUserApi(fake.client).user(TOKEN, "~sircmpwn").value()

        assertThat(profile.login).isEqualTo("~sircmpwn")
        assertThat(profile.website).isEqualTo("https://drewdevault.com")
        assertThat(profile.location).isEqualTo("Amsterdam")
        assertThat(profile.bio).isEqualTo("Hacker")
        assertThat(profile.publicRepos).isEqualTo(2)
        assertThat(fake.requests.first().string("owner")).isEqualTo("sircmpwn")
    }

    @Test
    fun repositories_page_through_and_keep_the_owners_tilde() = runTest {
        val fake = FakeSourceHut(listOf("repositories" to repositories))

        val repos = SourceHutUserApi(fake.client).repos(TOKEN, "sircmpwn", mine = false, organization = false).value()

        assertThat(repos.map { it.id }).containsExactly(
            RepoId("~sircmpwn", "scdoc", ForgeInstance.SourceHut),
            RepoId("~sircmpwn", "secret", ForgeInstance.SourceHut),
        ).inOrder()
        assertThat(repos.last().isPrivate).isTrue()
    }

    @Test
    fun signing_in_reads_who_the_token_is() = runTest {
        val fake = FakeSourceHut(listOf("me" to { _ -> """{"data":{"me":{"canonicalName":"~alice"}}}""" }))

        val user = SourceHutAuthApi(fake.client).fetchAuthenticatedUser(TOKEN).value()

        assertThat(user.login).isEqualTo("~alice")
        assertThat(fake.tokens.single()).isEqualTo("Bearer $TOKEN")
    }
}
