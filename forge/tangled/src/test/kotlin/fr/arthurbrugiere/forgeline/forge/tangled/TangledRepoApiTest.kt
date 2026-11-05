package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TangledRepoApiTest {
    private val core = RepoId("tangled.org", "core", ForgeInstance.Tangled)

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    private fun api(fake: FakeTangled) = TangledRepoApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc))

    @Test
    fun a_repository_is_found_by_its_owner_s_did_and_counted_by_its_own() = runTest {
        val fake = FakeTangled(
            mapOf(
                "com.atproto.identity.resolveHandle" to resolved,
                "org.tangled.temp.repo.getRepoByName" to coreRepo,
                "org.tangled.feed.countStars" to { """{"count":412,"distinctAuthors":412}""" },
                "org.tangled.repo.countForks" to { """{"count":37,"distinctAuthors":30}""" },
            ),
        )

        val details = api(fake).repo(null, core).value()

        assertThat(details.description).isEqualTo("Monorepo for Tangled")
        assertThat(details.homepage).isEqualTo("https://tangled.org")
        assertThat(details.topics).containsExactly("atproto", "git").inOrder()
        assertThat(details.stars).isEqualTo(412)
        assertThat(details.forks).isEqualTo(37)
        assertThat(details.defaultBranch).isEqualTo("master")
        assertThat(details.language).isEqualTo("Go")
        // What the app can't list stays out of the tabs.
        assertThat(details.listsConversations).isFalse()
        assertThat(details.listsReleases).isFalse()
        assertThat(details.hasActions).isFalse()
        assertThat(fake.asked).containsAtLeast(
            "com.atproto.identity.resolveHandle?handle=tangled.org",
            "org.tangled.temp.repo.getRepoByName?name=core&owner=$OWNER_DID",
            "org.tangled.feed.countStars?subject=$REPO_DID",
        )
        // Handles resolve on Bluesky's public AppView, which also has the picture of someone whose Tangled profile names
        // none; everything else is Tangled's.
        assertThat(fake.requests.first().url.host).isEqualTo("public.api.bsky.app")
        assertThat(fake.requests.drop(1).filterNot { "app.bsky.actor.getProfile" in it.url.encodedPath }.map { it.url.host }.toSet()).containsExactly("api.tangled.org")
    }

    @Test
    fun a_repository_kept_as_a_manifest_is_read_by_the_did_it_lives_at() = runTest {
        val fake = FakeTangled(
            mapOf(
                "com.atproto.identity.resolveHandle" to resolved,
                "org.tangled.temp.repo.getRepoByName" to coreManifest,
                "org.tangled.feed.countStars" to { """{"count":412,"distinctAuthors":412}""" },
                "org.tangled.temp.git.getTree" to { """{"entries":[{"name":"README.md","mode":"0100644","size":20}]}""" },
                "org.tangled.temp.git.getBlob" to { "# Tangled\n" },
            ),
        )
        val api = api(fake)

        val details = api.repo(null, core).value()
        val readme = api.readme(null, core).value()

        assertThat(details.description).isEqualTo("Monorepo for Tangled")
        assertThat(details.stars).isEqualTo(412)
        assertThat(readme?.markdown).isEqualTo("# Tangled\n")
        assertThat(fake.asked).containsAtLeast(
            "org.tangled.feed.countStars?subject=$REPO_DID",
            "org.tangled.temp.git.getBlob?download=false&path=README.md&ref=HEAD&repo=$REPO_DID",
        )
    }

    @Test
    fun counts_tangled_cannot_give_leave_the_repository_readable() = runTest {
        val fake = FakeTangled(mapOf("com.atproto.identity.resolveHandle" to resolved, "org.tangled.temp.repo.getRepoByName" to coreRepo))

        val details = api(fake).repo(null, core).value()

        assertThat(details.stars).isEqualTo(0)
        assertThat(details.forks).isEqualTo(0)
    }

    @Test
    fun a_missing_repository_is_a_404() = runTest {
        val fake = FakeTangled(mapOf("com.atproto.identity.resolveHandle" to resolved))

        val result = api(fake).repo(null, RepoId("tangled.org", "nothing", ForgeInstance.Tangled))

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.Http(404, "repository not found"))
    }

    @Test
    fun a_folder_lists_folders_first_with_git_s_modes_read() = runTest {
        val tree = """
            {"entries":[
              {"name":"go.mod","mode":"0100644","size":1200},
              {"name":"appview","mode":"0040000","size":0},
              {"name":"run.sh","mode":"0100755","size":80},
              {"name":"link","mode":"0120000","size":12},
              {"name":"vendor-sub","mode":"0160000","size":0}
            ]}
        """.trimIndent()
        val fake = FakeTangled(mapOf("com.atproto.identity.resolveHandle" to resolved, "org.tangled.temp.repo.getRepoByName" to coreRepo, "org.tangled.temp.git.getTree" to { tree }))

        val files = api(fake).contents(null, core, "cmd/", "master").value()

        assertThat(files.map { it.path }).containsExactly("cmd/appview", "cmd/go.mod", "cmd/link", "cmd/run.sh", "cmd/vendor-sub").inOrder()
        assertThat(files.map { it.type }).containsExactly(RepoFileType.DIR, RepoFileType.FILE, RepoFileType.SYMLINK, RepoFileType.FILE, RepoFileType.SUBMODULE).inOrder()
        assertThat(fake.asked.last()).isEqualTo("org.tangled.temp.git.getTree?path=cmd&ref=master&repo=$REPO_DID")
    }

    @Test
    fun the_readme_is_found_among_the_root_files_and_read_raw() = runTest {
        val tree = """{"entries":[{"name":"readme.txt","mode":"0100644","size":3},{"name":"README.md","mode":"0100644","size":20},{"name":"docs","mode":"0040000","size":0}]}"""
        val fake = FakeTangled(
            mapOf(
                "com.atproto.identity.resolveHandle" to resolved,
                "org.tangled.temp.repo.getRepoByName" to coreRepo,
                "org.tangled.temp.git.getTree" to { tree },
                "org.tangled.temp.git.getBlob" to { "# Tangled\n" },
            ),
        )

        val readme = api(fake).readme(null, core).value()

        assertThat(readme?.path).isEqualTo("README.md")
        assertThat(readme?.markdown).isEqualTo("# Tangled\n")
        assertThat(fake.asked.last()).isEqualTo("org.tangled.temp.git.getBlob?download=false&path=README.md&ref=HEAD&repo=$REPO_DID")
    }

    @Test
    fun branches_and_tags_are_read_page_by_page() = runTest {
        val fake = FakeTangled(
            mapOf(
                "com.atproto.identity.resolveHandle" to resolved,
                "org.tangled.temp.repo.getRepoByName" to coreRepo,
                "org.tangled.temp.git.listBranches" to { request ->
                    if (request.url.parameters["cursor"] == null) {
                        """{"branches":[{"name":"master","oid":"a","message":"m","author":$SIGNATURE,"committer":$SIGNATURE}],"cursor":"next","total":2,"defaultBranch":"master"}"""
                    } else {
                        """{"branches":[{"name":"dev","oid":"b","message":"m","author":$SIGNATURE,"committer":$SIGNATURE}],"total":2}"""
                    }
                },
                "org.tangled.temp.git.listTags" to { """{"tags":[{"name":"v1.9.0","oid":"c","message":"","author":$SIGNATURE},{"name":"v1.10.0","oid":"d","message":"","author":$SIGNATURE}],"total":2}""" },
            ),
        )

        val refs = api(fake).refs(null, core).value()

        assertThat(refs.branches).containsExactly("dev", "master").inOrder()
        assertThat(refs.tags).containsExactly("v1.10.0", "v1.9.0").inOrder()
    }

    @Test
    fun languages_are_shares_of_the_bytes() = runTest {
        val fake = FakeTangled(mapOf("com.atproto.identity.resolveHandle" to resolved, "org.tangled.temp.repo.getRepoByName" to coreRepo))

        assertThat(api(fake).languages(null, core).value()).containsExactly(RepoLanguage("Go", 0.9), RepoLanguage("HTML", 0.1)).inOrder()
    }

    @Test
    fun what_tangled_does_not_list_is_unsupported_without_asking() = runTest {
        val fake = FakeTangled(emptyMap())
        val api = api(fake)

        assertThat(api.issues(null, core)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(api.releases(null, core)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(api.workflowRuns(null, core)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(fake.requests).isEmpty()
        assertThat(api.rawBaseUrl(core, "master")).isEqualTo("https://tangled.org/tangled.org/core/raw/master/")
        assertThat(api.blobBaseUrl(core, "master")).isEqualTo("https://tangled.org/tangled.org/core/blob/master/")
    }
}
