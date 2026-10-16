package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Release notes below are real, as their projects published them in October 2026. */
class ReleasePreviewTest {
    @Test
    fun a_release_is_measured_by_the_first_number_that_changed() {
        assertThat(releaseJump("v3.3.3", "v3.3.1")).isEqualTo(ReleaseJump.PATCH)
        assertThat(releaseJump("v1.25.0-pre", "v1.24.2")).isEqualTo(ReleaseJump.MINOR)
        assertThat(releaseJump("v2.0.0", "v1.9.4")).isEqualTo(ReleaseJump.MAJOR)
        assertThat(releaseJump("12.2", "v12.1")).isEqualTo(ReleaseJump.MINOR)
        assertThat(releaseJump("0.13.1", "0.13.0")).isEqualTo(ReleaseJump.PATCH)
        // A calendar version: a new month reads as minor, a fix within it as a patch.
        assertThat(releaseJump("2026.11.0", "2026.10.3")).isEqualTo(ReleaseJump.MINOR)
        assertThat(releaseJump("2026.10.1", "2026.10.0")).isEqualTo(ReleaseJump.PATCH)
        // A missing trailing number is a zero.
        assertThat(releaseJump("v1.3", "v1.2.9")).isEqualTo(ReleaseJump.MINOR)
        assertThat(releaseJump("release-4.1", "release-4.0")).isEqualTo(ReleaseJump.MINOR)
    }

    @Test
    fun a_release_with_no_number_to_compare_says_nothing_of_its_size() {
        assertThat(releaseJump("nightly", "v0.12.6")).isNull()
        assertThat(releaseJump("v0.12.6", null)).isNull()
        assertThat(releaseJump("v1.2.0", "v1.2.0-rc.1")).isNull()
    }

    @Test
    fun the_summary_is_the_opening_paragraph_when_the_notes_open_with_prose() {
        val immich = """
            Well, you know how it goes... Misclick once and a release is sacrificed 😅 The second patch release `v3.3.3` has some more bug fixes, notably a major performance improvement for the explore page. 

            ## What's Changed
            ### 🐛 Bug fixes
            * fix(mobile): show the memories date in every app language by @santoshakil in https://github.com/immich-app/immich/pull/32259

            **Full Changelog**: https://github.com/immich-app/immich/compare/v3.3.1...v3.3.3
        """.trimIndent()
        assertThat(releaseSummary(immich)).isEqualTo(
            "Well, you know how it goes... Misclick once and a release is sacrificed 😅 The second patch release v3.3.3 has some " +
                "more bug fixes, notably a major performance improvement for the explore page.",
        )
    }

    @Test
    fun a_title_heading_is_skipped_for_the_paragraph_under_it() {
        val jellyfin = """
            # :rocket: Jellyfin Server 12.2

            We are pleased to announce the latest stable release of Jellyfin, version 12.2! This minor release brings several bugfixes.

            ## Changelog (51)
        """.trimIndent()
        assertThat(releaseSummary(jellyfin)).isEqualTo(
            "We are pleased to announce the latest stable release of Jellyfin, version 12.2! This minor release brings several bugfixes.",
        )
    }

    @Test
    fun notes_that_are_only_a_list_give_their_first_changes_without_credits() {
        val homeAssistant = """
            - Split Matter TemperatureOffset range by Thermostat ClusterRevision ([@lboue] - [#180517]) ([matter docs])
            - Bump pywaze to 1.2.4 ([@eifinger] - [#184663]) ([waze_travel_time docs]) (dependency)
            - Retry failed Analytics insights refresh ([@chemelli74] - [#184731]) ([analytics_insights docs])
            - Fix WLED color modes not following a changed LED setup ([@frenck] - [#184778]) ([wled docs])
        """.trimIndent()
        assertThat(releaseSummary(homeAssistant)).isEqualTo(
            "Split Matter TemperatureOffset range by Thermostat ClusterRevision · Bump pywaze to 1.2.4 · Retry failed Analytics insights refresh",
        )
        val uv = """
            ## Release Notes

            Released on 2026-10-14.

            ### Enhancements

            - Ignore `Requires-Python` upper bounds in `uv pip check` ([#22500](https://github.com/astral-sh/uv/pull/22500))
            - Report missing trusted hashes in resolution errors ([#22241](https://github.com/astral-sh/uv/pull/22241))
        """.trimIndent()
        assertThat(releaseSummary(uv)).isEqualTo(
            "Ignore Requires-Python upper bounds in uv pip check · Report missing trusted hashes in resolution errors",
        )
    }

    @Test
    fun links_keep_their_words_and_lose_their_addresses() {
        val netbird = """
            ## What's Changed

            [client] Keep interface discovery out of the bind open path (https://github.com/netbirdio/netbird/pull/8226) (https://github.com/netbirdio/netbird/pull/8227)

            **Full Changelog**: https://github.com/netbirdio/netbird/compare/v0.81.0...v0.81.1
        """.trimIndent()
        assertThat(releaseSummary(netbird)).isEqualTo("[client] Keep interface discovery out of the bind open path")
        assertThat(releaseSummary("Read the [migration guide](https://example.org/guide) before upgrading from version one."))
            .isEqualTo("Read the migration guide before upgrading from version one.")
    }

    @Test
    fun code_and_quotes_never_open_a_summary() {
        val neovim = """
            ```
            NVIM v0.13.0-dev-1868+gbe5167e21c
            Build type: RelWithDebInfo
            ```

            ## Release notes

            - [Changelog](https://github.com/neovim/neovim/commit/be5167e) (fixes + features)
            - [News](./runtime/doc/news.txt) (`:help news` in Nvim)
        """.trimIndent()
        assertThat(releaseSummary(neovim)).isEqualTo("Changelog (fixes + features) · News (:help news in Nvim)")
        val ghostty = """
            > [!WARNING]
            > **This is a nightly build, not a tagged release.**

            This tip release is automatically built and generated on every commit to main that passes tests.
        """.trimIndent()
        assertThat(releaseSummary(ghostty)).isEqualTo("This tip release is automatically built and generated on every commit to main that passes tests.")
    }

    @Test
    fun a_long_opening_is_cut_at_a_word() {
        val summary = releaseSummary("word ".repeat(100).trim(), maxLength = 40)!!
        assertThat(summary).isEqualTo("word word word word word word word word…")
    }

    @Test
    fun empty_notes_have_no_summary() {
        assertThat(releaseSummary(null)).isNull()
        assertThat(releaseSummary("  \n")).isNull()
        assertThat(releaseSummary("**Full Changelog**: https://github.com/a/b/compare/v1...v2")).isNull()
    }

    @Test
    fun the_first_picture_in_the_notes_is_found_in_markdown_or_html() {
        assertThat(firstImage("Look:\n\n![The new map](https://example.org/map.png \"Map\")\n\n![second](https://example.org/2.png)"))
            .isEqualTo("https://example.org/map.png")
        assertThat(firstImage("<p align=\"center\"><img width=\"600\" src=\"https://example.org/hero.webp\"></p>\n![x](https://example.org/later.png)"))
            .isEqualTo("https://example.org/hero.webp")
        // Only pictures that load over https: a relative path means nothing outside the forge's page.
        assertThat(firstImage("![logo](./docs/logo.png)")).isNull()
        assertThat(firstImage(null)).isNull()
    }

    @Test
    fun languages_become_shares_of_the_whole_largest_first() {
        val shares = languageShares(mapOf("Kotlin" to 158_348.0, "TypeScript" to 6_118_914.0, "Shell" to 0.0))

        assertThat(shares.map { it.name }).containsExactly("TypeScript", "Kotlin").inOrder()
        assertThat(shares.sumOf { it.share }).isWithin(1e-9).of(1.0)
        assertThat(shares.first().share).isWithin(1e-4).of(0.9748)
        assertThat(languageShares(emptyMap())).isEmpty()
    }
}
