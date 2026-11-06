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

    @Test
    fun a_forge_at_the_root_of_its_server_owns_every_path_there() {
        val forge = ForgeInstance(ForgeType.FORGEJO, "git.example.org")

        assertThat(forge.server).isEqualTo("git.example.org")
        assertThat(forge.pathOf("git.example.org", "/acme/rocket")).isEqualTo("/acme/rocket")
        assertThat(forge.pathOf("www.git.example.org", "/acme")).isEqualTo("/acme")
        assertThat(forge.pathOf("example.org", "/acme")).isNull()
    }

    @Test
    fun a_forge_under_a_path_owns_only_what_lies_under_it() {
        val forge = ForgeInstance(ForgeType.FORGEJO, "example.org/git")

        assertThat(forge.server).isEqualTo("example.org")
        assertThat(forge.webUrl).isEqualTo("https://example.org/git")
        assertThat(forge.pathOf("example.org", "/git/acme/rocket")).isEqualTo("/acme/rocket")
        assertThat(forge.pathOf("Example.org", "/Git/acme")).isEqualTo("/acme")
        assertThat(forge.pathOf("example.org", "/git")).isEmpty()
        assertThat(forge.pathOf("example.org", "/git/")).isEqualTo("/")
        assertThat(forge.pathOf("example.org", "/gitea/acme")).isNull()
        assertThat(forge.pathOf("example.org", "/acme/rocket")).isNull()
    }
}
