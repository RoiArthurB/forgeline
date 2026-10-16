package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProfileLinkTest {
    private fun label(raw: String, mastodon: Boolean = false) = ProfileLink.of(raw, mastodon)?.let { it.kind to it.label }

    @Test
    fun chat_links_github_calls_generic_are_recognised_by_their_address() {
        // The owner's own three, as GitHub lists them: provider "generic" for each (2026-10-16).
        assertThat(label("https://signal.me/#eu/abcdefghijklmnop")).isEqualTo(LinkKind.SIGNAL to "Signal")
        assertThat(label("https://matrix.to/#/@ana:matrix.org")).isEqualTo(LinkKind.MATRIX to "@ana:matrix.org")
        assertThat(label("https://xmpp.link/#ana@jabber.fr?message")).isEqualTo(LinkKind.XMPP to "ana@jabber.fr")
        assertThat(label("xmpp:ana@jabber.fr")).isEqualTo(LinkKind.XMPP to "ana@jabber.fr")
    }

    @Test
    fun social_accounts_read_as_their_handles() {
        assertThat(label("https://x.com/anisayari")).isEqualTo(LinkKind.X to "@anisayari")
        assertThat(label("https://twitter.com/octocat/")).isEqualTo(LinkKind.X to "@octocat")
        assertThat(label("https://bsky.app/profile/ana.bsky.social")).isEqualTo(LinkKind.BLUESKY to "@ana.bsky.social")
        assertThat(label("https://www.youtube.com/@anisayari")).isEqualTo(LinkKind.YOUTUBE to "@anisayari")
        assertThat(label("https://www.linkedin.com/in/ana-dupont/")).isEqualTo(LinkKind.LINKEDIN to "ana-dupont")
        assertThat(label("https://www.reddit.com/user/ana")).isEqualTo(LinkKind.REDDIT to "u/ana")
        assertThat(label("https://orcid.org/0000-0002-1825-0097")).isEqualTo(LinkKind.ORCID to "0000-0002-1825-0097")
    }

    @Test
    fun a_fediverse_account_is_known_on_any_server() {
        assertThat(label("https://mastodon.social/@ana")).isEqualTo(LinkKind.MASTODON to "@ana@mastodon.social")
        assertThat(label("https://fosstodon.org/@ana")).isEqualTo(LinkKind.MASTODON to "@ana@fosstodon.org")
        // Where the forge says it's Mastodon, an address without "@" is believed too.
        assertThat(label("https://social.example.org/users/ana", mastodon = true)).isEqualTo(LinkKind.MASTODON to "@ana@social.example.org")
    }

    @Test
    fun anything_else_is_a_website_shown_without_its_scheme() {
        assertThat(label("https://ana.dev/")).isEqualTo(LinkKind.WEB to "ana.dev")
        assertThat(label("blog.example.org/ana")).isEqualTo(LinkKind.WEB to "blog.example.org/ana")
        assertThat(label("  ")).isNull()
    }

    @Test
    fun an_address_that_only_hides_the_real_one_is_left_out() {
        assertThat(ProfileLink.email("ana@example.org")).isEqualTo(ProfileLink(LinkKind.EMAIL, "mailto:ana@example.org", "ana@example.org"))
        assertThat(ProfileLink.email("12345+ana@users.noreply.github.com")).isNull()
        assertThat(ProfileLink.email("ana@noreply.codeberg.org")).isNull()
        assertThat(ProfileLink.email("")).isNull()
    }

    @Test
    fun the_same_place_said_twice_shows_once() {
        val links = listOfNotNull(ProfileLink.of("https://x.com/ana"), ProfileLink.of("https://twitter.com/Ana"), ProfileLink.of("https://ana.dev"))

        assertThat(links.distinctPlaces().map { it.label }).containsExactly("@ana", "ana.dev").inOrder()
    }
}
