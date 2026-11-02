package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TangledTrendingApiTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val trending = """
        {"people":[],"repos":[
          {"did":"$REPO_DID","owner":{"did":"$OWNER_DID","handle":"tangled.org","avatar":"https://avatar.example/t.jpg"},
           "slug":"core","createdAt":"2025-03-01T10:00:00Z","description":"Monorepo for Tangled","forkCount":37,"starCount":412,"primaryLanguage":"Go"},
          {"did":"did:plc:other","owner":{"did":"did:plc:oppi","handle":"oppi.li"},"slug":"tools","starCount":9}
        ]}
    """.trimIndent()

    private fun stars(vararg ages: String) = """{"items":[${ages.joinToString(",") { """{"uri":"at://x/sh.tangled.feed.star/1","value":{"createdAt":"$it"}}""" }}]}"""

    @Test
    fun the_list_keeps_tangled_s_order_and_counts_the_stars_each_gained_in_the_period() = runTest {
        val fake = FakeTangled(
            mapOf(
                "org.tangled.actor.getTrending" to { trending },
                "org.tangled.feed.listStars" to { request ->
                    if (request.url.parameters["subject"] == REPO_DID) {
                        stars("2026-11-02T11:00:00Z", "2026-11-01T13:00:00Z", "2026-10-30T09:00:00Z", "2026-09-01T00:00:00Z")
                    } else {
                        stars("2026-10-01T00:00:00Z")
                    }
                },
            ),
        )
        val api = TangledTrendingApi(fake.client, TangledIdentity(fake.client), clock)

        val daily = (api.trending(TrendingPeriod.DAILY) as ForgeResult.Success).value
        val weekly = (api.trending(TrendingPeriod.WEEKLY) as ForgeResult.Success).value

        assertThat(daily.map { it.id }).containsExactly(RepoId("tangled.org", "core", ForgeInstance.Tangled), RepoId("oppi.li", "tools", ForgeInstance.Tangled)).inOrder()
        assertThat(daily.map { it.periodStars }).containsExactly(2, 0).inOrder()
        assertThat(weekly.map { it.periodStars }).containsExactly(3, 0).inOrder()
        val core = daily.first()
        assertThat(core.stars).isEqualTo(412)
        assertThat(core.forks).isEqualTo(37)
        assertThat(core.language).isEqualTo("Go")
        assertThat(core.ownerAvatarUrl).isEqualTo("https://avatar.example/t.jpg")
        assertThat(fake.asked).contains("org.tangled.feed.listStars?limit=100&order=desc&subject=$REPO_DID")
    }

    @Test
    fun the_owners_it_names_need_no_resolving_afterwards() = runTest {
        val fake = FakeTangled(
            mapOf(
                "org.tangled.actor.getTrending" to { trending },
                "org.tangled.feed.listStars" to { stars() },
                "org.tangled.temp.repo.getRepoByName" to coreRepo,
            ),
        )
        val identity = TangledIdentity(fake.client)
        TangledTrendingApi(fake.client, identity, clock).trending(TrendingPeriod.DAILY)

        val repo = TangledRepoApi(fake.client, identity).repo(null, RepoId("tangled.org", "core", ForgeInstance.Tangled))

        assertThat(repo).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(fake.asked.none { it.startsWith("com.atproto.identity.resolveHandle") }).isTrue()
    }
}
