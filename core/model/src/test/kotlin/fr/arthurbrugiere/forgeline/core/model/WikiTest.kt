package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WikiTest {
    private val gama = RepoId("gama-platform", "gama")
    private val foot = RepoId("dnkl", "foot", ForgeInstance.Codeberg)
    private val fdroid = RepoId("fdroid", "wiki", ForgeInstance.GitLab)

    @Test
    fun each_forge_keeps_its_wiki_pages_and_their_files_where_its_site_does() {
        assertThat(gama.wikiBaseUrl).isEqualTo("https://github.com/gama-platform/gama/wiki/")
        assertThat(gama.wikiRawBaseUrl).isEqualTo("https://raw.githubusercontent.com/wiki/gama-platform/gama/")
        assertThat(foot.wikiBaseUrl).isEqualTo("https://codeberg.org/dnkl/foot/wiki/")
        assertThat(foot.wikiRawBaseUrl).isEqualTo("https://codeberg.org/dnkl/foot/wiki/raw/")
        assertThat(fdroid.wikiBaseUrl).isEqualTo("https://gitlab.com/fdroid/wiki/-/wikis/")
    }

    @Test
    fun a_link_under_the_wiki_opens_its_page_and_any_other_doesn_t() {
        assertThat(gama.wikiPageOf("https://github.com/gama-platform/gama/wiki/projects")).isEqualTo("projects")
        assertThat(gama.wikiPageOf("https://github.com/gama-platform/gama/wiki/Installation#linux")).isEqualTo("Installation")
        assertThat(fdroid.wikiPageOf("https://gitlab.com/fdroid/wiki/-/wikis/Build-System/CI")).isEqualTo("Build-System/CI")
        // The wiki itself, its own lists and files, another repository's wiki, and the rest of the site.
        assertThat(gama.wikiPageOf("https://github.com/gama-platform/gama/wiki/")).isNull()
        assertThat(gama.wikiPageOf("https://github.com/gama-platform/gama/wiki/_pages")).isNull()
        assertThat(foot.wikiPageOf("https://codeberg.org/dnkl/foot/wiki/raw/shot.png")).isNull()
        assertThat(gama.wikiPageOf("https://github.com/other/gama/wiki/Home")).isNull()
        assertThat(gama.wikiPageOf("https://github.com/gama-platform/gama/issues/1")).isNull()
    }

    @Test
    fun a_page_without_a_title_is_named_by_its_key() {
        assertThat(wikiTitleOf("Getting-Started")).isEqualTo("Getting Started")
        assertThat(wikiTitleOf("Build-System/CI")).isEqualTo("CI")
    }
}
