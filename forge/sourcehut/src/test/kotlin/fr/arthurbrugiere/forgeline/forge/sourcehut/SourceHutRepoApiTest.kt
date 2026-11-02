package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class SourceHutRepoApiTest {
    private val scdoc = RepoId("~sircmpwn", "scdoc", ForgeInstance.SourceHut)

    private fun <T> ForgeResult<T>.value(): T = if (this is ForgeResult.Success) value else error("$this")

    @Test
    fun a_repository_reads_with_the_token_and_its_owner_without_tilde() = runTest {
        val fake = FakeSourceHut(
            listOf(
                "HEAD" to { _ ->
                    inRepository("""{"name":"scdoc","description":"Tool for generating roff manual pages","visibility":"PUBLIC","updated":"2026-10-01T12:00:00Z","HEAD":{"name":"refs/heads/master"}}""")
                },
            ),
        )

        val repo = SourceHutRepoApi(fake.client).repo(TOKEN, scdoc).value()

        assertThat(repo.description).isEqualTo("Tool for generating roff manual pages")
        assertThat(repo.defaultBranch).isEqualTo("master")
        assertThat(repo.pushedAt).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"))
        assertThat(repo.isEmpty).isFalse()
        // Tickets, patches and builds are other services: their tabs stay away. Tags are its releases.
        assertThat(repo.listsConversations).isFalse()
        assertThat(repo.listsReleases).isTrue()
        assertThat(repo.hasActions).isFalse()
        assertThat(fake.requests.single().string("owner")).isEqualTo("sircmpwn")
        assertThat(fake.requests.single().string("name")).isEqualTo("scdoc")
        assertThat(fake.tokens.single()).isEqualTo("Bearer $TOKEN")
    }

    @Test
    fun nothing_is_asked_without_a_token() = runTest {
        val fake = FakeSourceHut(emptyList())

        assertThat(SourceHutRepoApi(fake.client).repo(null, scdoc)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(fake.requests).isEmpty()
    }

    @Test
    fun a_missing_person_or_repository_is_a_404() = runTest {
        val noUser = FakeSourceHut(listOf("HEAD" to { _ -> """{"data":{"user":null}}""" }))
        val noRepo = FakeSourceHut(listOf("HEAD" to { _ -> """{"data":{"user":{"repository":null}}}""" }))

        assertThat(((SourceHutRepoApi(noUser.client).repo(TOKEN, scdoc) as ForgeResult.Failure).error as ForgeError.Http).status).isEqualTo(404)
        assertThat(((SourceHutRepoApi(noRepo.client).repo(TOKEN, scdoc) as ForgeResult.Failure).error as ForgeError.Http).status).isEqualTo(404)
    }

    @Test
    fun a_refused_token_signs_out() = runTest {
        val fake = FakeSourceHut(listOf("HEAD" to { _ -> """{"errors":[{"message":"Invalid or expired OAuth 2.0 bearer token","extensions":{"code":"ERR_UNAUTHORIZED"}}]}""" }))

        assertThat(SourceHutRepoApi(fake.client).repo(TOKEN, scdoc)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    @Test
    fun the_root_folder_pages_through_its_entries_folders_first() = runTest {
        val fake = FakeSourceHut(
            listOf(
                "revparse_single" to { request ->
                    if (request.string("cursor") == null) {
                        inRepository("""{"revparse_single":{"tree":{"entries":{"__typename":"TreeEntryCursor","results":[{"name":"Makefile","mode":33188},{"name":"doc","mode":16384}],"cursor":"abc"}}}}""")
                    } else {
                        inRepository("""{"revparse_single":{"tree":{"entries":{"__typename":"TreeEntryCursor","results":[{"name":"link","mode":40960},{"name":"vendor","mode":57344},{"name":"run.sh","mode":33261}],"cursor":null}}}}""")
                    }
                },
            ),
        )

        val files = SourceHutRepoApi(fake.client).contents(TOKEN, scdoc, "", "master").value()

        assertThat(files.map { it.path }).containsExactly("doc", "link", "Makefile", "run.sh", "vendor").inOrder()
        assertThat(files.associate { it.name to it.type }).containsExactly(
            "doc", RepoFileType.DIR, "link", RepoFileType.SYMLINK, "Makefile", RepoFileType.FILE, "run.sh", RepoFileType.FILE, "vendor", RepoFileType.SUBMODULE,
        )
        assertThat(fake.requests.map { it.string("rev") }).containsExactly("master", "master")
        assertThat(fake.requests.map { it.string("cursor") }).containsExactly(null, "abc").inOrder()
    }

    @Test
    fun a_sub_folder_is_read_by_its_path() = runTest {
        val fake = FakeSourceHut(
            listOf("path(" to { _ -> inRepository("""{"path":{"object":{"__typename":"Tree","type":"TREE","entries":{"__typename":"TreeEntryCursor","results":[{"name":"scdoc.5.scd","mode":33188}],"cursor":null}}}}""") }),
        )

        val files = SourceHutRepoApi(fake.client).contents(TOKEN, scdoc, "doc/", "master").value()

        assertThat(files.single().path).isEqualTo("doc/scdoc.5.scd")
        assertThat(fake.requests.single().string("path")).isEqualTo("doc")
    }

    @Test
    fun a_small_file_is_read_inline_and_a_large_one_from_its_content() = runTest {
        val contentUrl = "https://git.sr.ht/query/blob/1/abcdef"
        val fake = FakeSourceHut(
            listOf(
                "path(" to { request ->
                    if (request.string("path") == "README.md") {
                        inRepository("""{"path":{"object":{"__typename":"TextBlob","type":"BLOB","size":20,"text":"# scdoc\n\nman pages","content":"$contentUrl"}}}""")
                    } else {
                        inRepository("""{"path":{"object":{"__typename":"TextBlob","type":"BLOB","size":200000,"text":"truncated","content":"$contentUrl"}}}""")
                    }
                },
            ),
            blobs = mapOf(contentUrl to "the whole file"),
        )
        val api = SourceHutRepoApi(fake.client)

        assertThat(api.fileText(TOKEN, scdoc, "README.md", "master").value()).isEqualTo("# scdoc\n\nman pages")
        assertThat(api.fileText(TOKEN, scdoc, "big.txt", "master").value()).isEqualTo("the whole file")
        // The content's address asks for the same token.
        assertThat(fake.tokens.last()).isEqualTo("Bearer $TOKEN")
    }

    @Test
    fun the_readme_is_found_in_the_root_folder() = runTest {
        val fake = FakeSourceHut(
            listOf(
                "revparse_single" to { _ -> inRepository("""{"revparse_single":{"tree":{"entries":{"__typename":"TreeEntryCursor","results":[{"name":"README","mode":33188},{"name":"README.md","mode":33188}],"cursor":null}}}}""") },
                "path(" to { _ -> inRepository("""{"path":{"object":{"__typename":"TextBlob","type":"BLOB","size":7,"text":"# scdoc","content":"x"}}}""") },
            ),
        )

        val readme = SourceHutRepoApi(fake.client).readme(TOKEN, scdoc).value()

        assertThat(readme?.path).isEqualTo("README.md")
        assertThat(readme?.markdown).isEqualTo("# scdoc")
    }

    @Test
    fun an_empty_repository_has_no_readme() = runTest {
        val fake = FakeSourceHut(listOf("revparse_single" to { _ -> """{"errors":[{"message":"reference not found"}],"data":{"user":{"repository":{"revparse_single":null}}}}""" }))

        assertThat(SourceHutRepoApi(fake.client).readme(TOKEN, scdoc).value()).isNull()
    }

    @Test
    fun branches_and_tags_come_from_the_refs() = runTest {
        val fake = FakeSourceHut(
            listOf(
                "references" to { _ ->
                    inRepository("""{"references":{"results":[{"name":"refs/heads/master"},{"name":"refs/heads/dev"},{"name":"refs/tags/1.9.0"},{"name":"refs/tags/1.11.3"},{"name":"refs/notes/commits"}],"cursor":null}}""")
                },
            ),
        )

        val refs = SourceHutRepoApi(fake.client).refs(TOKEN, scdoc).value()

        assertThat(refs.branches).containsExactly("dev", "master").inOrder()
        assertThat(refs.tags).containsExactly("1.11.3", "1.9.0").inOrder()
    }

    @Test
    fun tags_are_releases_newest_first_with_their_message_and_files() = runTest {
        val fake = FakeSourceHut(
            listOf(
                "references" to { _ ->
                    inRepository(
                        """{"references":{"results":[
                          {"__typename":"Reference","name":"refs/heads/master","follow":{"__typename":"Commit","type":"COMMIT","committer":{"time":"2026-10-01T00:00:00Z"}},"artifacts":{"results":[]}},
                          {"__typename":"Reference","name":"refs/tags/1.11.2","follow":{"__typename":"Tag","type":"TAG","message":"scdoc 1.11.2\n","tagger":{"time":"2025-01-01T00:00:00Z"}},"artifacts":{"results":[]}},
                          {"__typename":"Reference","name":"refs/tags/1.11.3","follow":{"__typename":"Tag","type":"TAG","message":"scdoc 1.11.3\n\nFixes.\n","tagger":{"time":"2026-02-01T00:00:00Z"}},
                           "artifacts":{"results":[{"filename":"scdoc-1.11.3.tar.gz.sig","size":566,"url":"https://git.sr.ht/~sircmpwn/scdoc/refs/download/1.11.3/scdoc-1.11.3.tar.gz.sig"}]}},
                          {"__typename":"Reference","name":"refs/tags/light","follow":{"__typename":"Commit","type":"COMMIT","committer":{"time":"2024-01-01T00:00:00Z"}},"artifacts":{"results":[]}}
                        ],"cursor":null}}""",
                    )
                },
            ),
        )

        val releases = SourceHutRepoApi(fake.client).releases(TOKEN, scdoc).value()

        assertThat(releases.map { it.tag }).containsExactly("1.11.3", "1.11.2", "light").inOrder()
        val latest = releases.first()
        assertThat(latest.isLatest).isTrue()
        assertThat(latest.body).isEqualTo("scdoc 1.11.3\n\nFixes.")
        assertThat(latest.assets.single().name).isEqualTo("scdoc-1.11.3.tar.gz.sig")
        assertThat(latest.tarUrl).isEqualTo("https://git.sr.ht/~sircmpwn/scdoc/archive/1.11.3.tar.gz")
        assertThat(latest.webUrl).isEqualTo("https://git.sr.ht/~sircmpwn/scdoc/refs/1.11.3")
        // A light tag has no message of its own.
        assertThat(releases.last().body).isNull()
    }

    @Test
    fun one_release_is_read_by_its_tag() = runTest {
        val fake = FakeSourceHut(
            listOf("reference(" to { _ -> inRepository("""{"reference":{"__typename":"Reference","name":"refs/tags/1.11.3","follow":{"__typename":"Tag","type":"TAG","message":"scdoc 1.11.3","tagger":{"time":"2026-02-01T00:00:00Z"}},"artifacts":{"results":[]}}}""") }),
        )

        val release = SourceHutRepoApi(fake.client).release(TOKEN, scdoc, "1.11.3").value()

        assertThat(release.tag).isEqualTo("1.11.3")
        assertThat(fake.requests.single().string("ref")).isEqualTo("refs/tags/1.11.3")
    }

    @Test
    fun an_answer_its_schema_does_not_allow_is_unreadable_rather_than_a_crash() = runTest {
        // A repository always has a visibility and an update time: this one has neither.
        val fake = FakeSourceHut(listOf("HEAD" to { _ -> inRepository("""{"name":"scdoc","HEAD":null}""") }))

        assertThat(SourceHutRepoApi(fake.client).repo(TOKEN, scdoc)).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    @Test
    fun a_binary_file_is_read_from_its_content() = runTest {
        val contentUrl = "https://git.sr.ht/query/blob/1/logo"
        val fake = FakeSourceHut(
            listOf("path(" to { _ -> inRepository("""{"path":{"object":{"__typename":"BinaryBlob","type":"BLOB","size":4,"content":"$contentUrl"}}}""") }),
            blobs = mapOf(contentUrl to "\u0089PNG"),
        )

        assertThat(SourceHutRepoApi(fake.client).fileText(TOKEN, scdoc, "logo.png", "master").value()).isEqualTo("\u0089PNG")
    }

    @Test
    fun a_folder_asked_as_a_file_is_refused() = runTest {
        val fake = FakeSourceHut(listOf("path(" to { _ -> inRepository("""{"path":{"object":{"__typename":"Tree","type":"TREE"}}}""") }))

        assertThat(((SourceHutRepoApi(fake.client).fileText(TOKEN, scdoc, "doc", "master") as ForgeResult.Failure).error as ForgeError.Http).status).isEqualTo(400)
    }

    @Test
    fun each_call_is_a_named_operation_checked_against_the_schema() = runTest {
        val fake = FakeSourceHut(listOf("HEAD" to { _ -> inRepository("""{"name":"scdoc","visibility":"PUBLIC","updated":"2026-10-01T12:00:00Z","HEAD":null}""") }))

        SourceHutRepoApi(fake.client).repo(TOKEN, scdoc)

        assertThat(fake.requests.single().query).startsWith("query Repo(")
    }
}
