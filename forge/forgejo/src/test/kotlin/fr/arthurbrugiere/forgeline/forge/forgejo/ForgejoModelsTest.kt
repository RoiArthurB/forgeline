package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * How the answers read through the models generated from Forgejo's API description, where every field may be missing:
 * the app's defaults for what it can do without, and an unreadable answer, never a crash, for what it can't.
 */
class ForgejoModelsTest {
    private val codeberg = Codeberg()
    private val forgejo = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)

    private fun repoApi(body: String) = with(codeberg) { ForgejoRepoApi(client { json(body) }, ForgeInstance.Codeberg) }

    @Test
    fun a_repository_that_says_little_reads_with_the_app_s_defaults() = runTest {
        val repo = (repoApi("""{"name":"forgejo","owner":{"login":"forgejo"}}""").repo(null, forgejo) as ForgeResult.Success).value

        assertThat(repo.id).isEqualTo(forgejo)
        assertThat(repo.defaultBranch).isEqualTo("main")
        assertThat(repo.hasIssues).isTrue()
        assertThat(repo.hasWiki).isFalse()
        assertThat(repo.stars).isEqualTo(0)
        assertThat(repo.openIssues).isNull()
        assertThat(repo.description).isNull()
    }

    @Test
    fun a_repository_without_its_name_is_unreadable() = runTest {
        val result = repoApi("""{"owner":{"login":"forgejo"},"stars_count":3}""").repo(null, forgejo)

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.Unreadable)
    }

    @Test
    fun an_issue_list_entry_without_its_number_makes_the_list_unreadable() = runTest {
        val result = repoApi("""[{"title":"No number","state":"open","created_at":"2026-09-29T15:09:06+02:00"}]""").issues(null, forgejo, IssueQuery())

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.Unreadable)
    }

    @Test
    fun the_pull_request_list_reads_merged_and_draft_from_the_pull_requests_themselves() = runTest {
        val pulls = """[
            {"number":2,"title":"Merged","state":"closed","merged":true,"created_at":"2026-09-29T15:09:06+02:00"},
            {"number":1,"title":"WIP: Draft","state":"open","draft":true,"created_at":"2026-09-28T15:09:06+02:00"}
        ]"""

        val listed = (repoApi(pulls).pullRequests(null, forgejo, IssueQuery()) as ForgeResult.Success).value

        assertThat(listed.map { it.state }).containsExactly(IssueState.MERGED, IssueState.OPEN).inOrder()
        assertThat(listed.map { it.isDraft }).containsExactly(false, true).inOrder()
        assertThat(listed.all { it.isPullRequest }).isTrue()
    }

    @Test
    fun a_release_without_its_tag_is_unreadable() = runTest {
        val result = repoApi("""[{"name":"No tag","draft":false}]""").releases(null, forgejo)

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.Unreadable)
    }

    @Test
    fun a_draft_release_is_left_out_and_one_without_archives_offers_none() = runTest {
        val releases = """[
            {"tag_name":"v2","draft":true},
            {"tag_name":"v1","hide_archive_links":true,"zipball_url":"https://codeberg.org/z.zip","tarball_url":"https://codeberg.org/t.tar.gz"}
        ]"""

        val listed = (repoApi(releases).releases(null, forgejo) as ForgeResult.Success).value

        assertThat(listed.map { it.tag }).containsExactly("v1")
        assertThat(listed.single().zipUrl).isNull()
        assertThat(listed.single().tarUrl).isNull()
    }
}
