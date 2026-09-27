package fr.arthurbrugiere.forgeline.core.data.search

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeSearchApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SearchRepositoryTest {
    private val api = FakeSearchApi()
    private val accounts = FakeAccountRepository()
    private val repository = SearchRepository(api, accounts)

    @Test
    fun signed_out_searches_anonymously() = runTest {
        repository.repositories("forge")

        assertThat(api.tokens).containsExactly(null)
    }

    @Test
    fun signed_in_searches_with_the_token() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t-me")

        repository.issues("bug", page = 2)
        repository.users("octo")

        assertThat(api.tokens).containsExactly("t-me", "t-me")
        assertThat(api.calls).containsExactly("issues:bug@2", "users:octo@1").inOrder()
    }
}
