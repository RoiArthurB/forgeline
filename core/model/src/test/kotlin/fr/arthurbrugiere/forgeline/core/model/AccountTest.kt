package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountTest {
    @Test
    fun account_ids_are_namespaced_by_forge_type_and_host() {
        assertThat(Account.idFor(ForgeInstance.GitHub, "octocat")).isEqualTo("github:github.com:octocat")
    }

    @Test
    fun account_id_is_case_insensitive_on_login() {
        assertThat(Account.idFor(ForgeInstance.GitHub, "OctoCat")).isEqualTo(Account.idFor(ForgeInstance.GitHub, "octocat"))
    }

    @Test
    fun the_same_login_on_two_forges_is_two_accounts() {
        assertThat(Account.idFor(ForgeInstance.Codeberg, "octocat")).isEqualTo("forgejo:codeberg.org:octocat")
        assertThat(Account.idFor(ForgeInstance.Codeberg, "octocat")).isNotEqualTo(Account.idFor(ForgeInstance.GitHub, "octocat"))
    }
}
