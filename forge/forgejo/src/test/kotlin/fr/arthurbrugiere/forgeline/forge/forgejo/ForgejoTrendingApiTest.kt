package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ForgejoTrendingApiTest {
    private val codeberg = Codeberg()
    private val url = "https://example.org/forgeline/codeberg/trending.json"

    private val file = """
        {"generatedAt":"2026-09-30T02:17:00Z",
         "daily":[{"owner":"ziglang","name":"zig","language":"Zig","stars":6712,"forks":928,"periodStars":31,"periodForks":2,
                   "ownerAvatarUrl":"https://codeberg.org/avatars/z"},
                  {"owner":"forgejo","name":"forgejo","stars":3000,"forks":600,"periodStars":12}],
         "weekly":[{"owner":"forgejo","name":"forgejo","stars":3000,"forks":600,"periodStars":80,"somethingNew":true}]}
    """.trimIndent()

    @Test
    fun reads_each_period_from_the_published_file_in_its_order() = runTest {
        val api = ForgejoTrendingApi(codeberg.client { with(codeberg) { json(file) } }, ForgeInstance.Codeberg, url)

        val daily = (api.trending(TrendingPeriod.DAILY) as ForgeResult.Success).value
        val weekly = (api.trending(TrendingPeriod.WEEKLY) as ForgeResult.Success).value
        val monthly = (api.trending(TrendingPeriod.MONTHLY) as ForgeResult.Success).value

        assertThat(daily.map { it.id }).containsExactly(
            RepoId("ziglang", "zig", ForgeInstance.Codeberg),
            RepoId("forgejo", "forgejo", ForgeInstance.Codeberg),
        ).inOrder()
        assertThat(daily[0].periodStars).isEqualTo(31)
        assertThat(daily[0].language).isEqualTo("Zig")
        assertThat(daily[0].ownerAvatarUrl).isEqualTo("https://codeberg.org/avatars/z")
        assertThat(weekly.single().periodStars).isEqualTo(80)
        assertThat(monthly).isEmpty()
        assertThat(codeberg.requests.map { it.url.toString() }.distinct()).containsExactly(url)
    }

    @Test
    fun a_missing_file_is_an_error_not_an_empty_list() = runTest {
        val api = ForgejoTrendingApi(codeberg.client { with(codeberg) { status(HttpStatusCode.NotFound) } }, ForgeInstance.Codeberg, url)

        val result = api.trending(TrendingPeriod.DAILY)

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.Http(404, null))
    }

    @Test
    fun a_broken_file_is_an_error() = runTest {
        val api = ForgejoTrendingApi(codeberg.client { with(codeberg) { text("<html>Not JSON</html>") } }, ForgeInstance.Codeberg, url)

        assertThat(api.trending(TrendingPeriod.DAILY)).isInstanceOf(ForgeResult.Failure::class.java)
    }
}
