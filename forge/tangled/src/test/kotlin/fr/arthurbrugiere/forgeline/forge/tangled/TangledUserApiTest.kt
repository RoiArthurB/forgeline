package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TangledUserApiTest {
    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun a_profile_reads_with_its_counts_and_links() = runTest {
        val fake = FakeTangled(
            mapOf(
                "com.atproto.identity.resolveHandle" to resolved,
                "org.tangled.actor.getProfile" to {
                    """{"uri":"at://$OWNER_DID/org.tangled.actor.profile/self","value":{"description":"Git, on the AT Protocol","location":"Helsinki",
                        "links":["https://blog.tangled.org","https://bsky.app/profile/tangled.org"],"isOrganization":true}}"""
                },
                "org.tangled.graph.countFollows" to { """{"count":1200,"distinctAuthors":1200}""" },
                "org.tangled.graph.countFollowsBy" to { """{"count":3,"distinctAuthors":3}""" },
                "org.tangled.repo.countRepos" to { """{"count":14,"distinctAuthors":1}""" },
            ),
        )

        val profile = TangledUserApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).user(null, "tangled.org").value()

        assertThat(profile.login).isEqualTo("tangled.org")
        assertThat(profile.bio).isEqualTo("Git, on the AT Protocol")
        assertThat(profile.location).isEqualTo("Helsinki")
        assertThat(profile.website).isEqualTo("https://blog.tangled.org")
        assertThat(profile.links).hasSize(1)
        assertThat(profile.isOrganization).isTrue()
        assertThat(profile.followers).isEqualTo(1200)
        assertThat(profile.following).isEqualTo(3)
        assertThat(profile.publicRepos).isEqualTo(14)
        // Regression: asked by the bare DID, which the index refuses ("regex failure"), so no profile ever read.
        assertThat(fake.asked).contains("org.tangled.actor.getProfile?actor=at://$OWNER_DID/org.tangled.actor.profile/self")
    }

    @Test
    fun someone_without_a_profile_record_is_still_someone() = runTest {
        val fake = FakeTangled(mapOf("com.atproto.identity.resolveHandle" to resolved))

        val profile = TangledUserApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).user(null, "@quiet.example").value()

        assertThat(profile.login).isEqualTo("quiet.example")
        assertThat(profile.bio).isNull()
        assertThat(profile.followers).isEqualTo(0)
    }

    @Test
    fun repositories_list_by_name_newest_first() = runTest {
        val fake = FakeTangled(
            mapOf(
                "com.atproto.identity.resolveHandle" to resolved,
                "org.tangled.repo.listRepos" to {
                    """{"items":[
                        {"uri":"at://$OWNER_DID/sh.tangled.repo/3abc","value":{"name":"core","knot":"k","description":"Monorepo","createdAt":"2025-03-01T10:00:00Z"}},
                        {"uri":"at://$OWNER_DID/sh.tangled.repo/website","value":{"knot":"k","source":"https://github.com/x/y","createdAt":"2025-01-01T10:00:00Z"}}
                    ]}"""
                },
            ),
        )

        val repos = TangledUserApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).repos(null, "tangled.org").value()

        assertThat(repos.map { it.id }).containsExactly(RepoId("tangled.org", "core", ForgeInstance.Tangled), RepoId("tangled.org", "website", ForgeInstance.Tangled)).inOrder()
        assertThat(repos.map { it.isFork }).containsExactly(false, true).inOrder()
        assertThat(fake.asked.last()).isEqualTo("org.tangled.repo.listRepos?limit=100&order=desc&subject=$OWNER_DID")
    }

    @Test
    fun repositories_kept_as_manifests_list_by_their_declared_name() = runTest {
        val fake = FakeTangled(
            mapOf(
                "com.atproto.identity.resolveHandle" to resolved,
                "org.tangled.repo.listRepos" to {
                    """{"items":[
                        {"uri":"at://did:plc:lyuzyoi42mf2driipmsiemsl/org.tangled.repo.manifest/self","value":{"${'$'}type":"org.tangled.repo.manifest","declaration":{"name":"infra","owner":"$OWNER_DID"},"createdAt":"2025-06-21T09:39:21Z"}},
                        {"uri":"at://$REPO_DID/org.tangled.repo.manifest/self","value":{"${'$'}type":"org.tangled.repo.manifest","declaration":{"name":"core","owner":"$OWNER_DID"},"description":"Monorepo","createdAt":"2025-02-23T18:43:18Z"}}
                    ]}"""
                },
            ),
        )

        val repos = TangledUserApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).repos(null, "tangled.org").value()

        assertThat(repos.map { it.id.name }).containsExactly("infra", "core").inOrder()
    }
}
