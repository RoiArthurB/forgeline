package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * github.com/trending has no API; this reads its HTML. Selectors target the markup as of 2026-09.
 * The nightly live test (`:forge:github:liveTest`) flags when GitHub changes it.
 */
object GitHubTrendingParser {
    private val count = Regex("""[\d,]+""")
    private val color = Regex("""background-color:\s*(#[0-9a-fA-F]{3,8})""")

    fun parse(html: String): List<TrendingRepo> =
        Jsoup.parse(html).select("article.Box-row").mapNotNull(::parseRow)

    private fun parseRow(row: Element): TrendingRepo? {
        val path = row.selectFirst("h2 a[href]")?.attr("href")?.trim('/') ?: return null
        val (owner, name) = path.split('/').takeIf { it.size == 2 } ?: return null
        return TrendingRepo(
            id = RepoId(owner, name, ForgeInstance.GitHub),
            description = row.selectFirst("p")?.text()?.trim()?.ifEmpty { null },
            language = row.selectFirst("[itemprop=programmingLanguage]")?.text()?.trim(),
            languageColor = row.selectFirst(".repo-language-color")?.attr("style")?.let { color.find(it)?.groupValues?.get(1) },
            stars = row.selectFirst("a[href$=/stargazers]")?.text().toCount(),
            forks = row.selectFirst("a[href$=/forks]")?.text().toCount(),
            periodStars = row.selectFirst(".float-sm-right")?.text().toCount(),
            builtBy = row.select("a[data-hovercard-type=user] img[alt]")
                .map { ForgeUser(login = it.attr("alt").removePrefix("@"), name = null, avatarUrl = it.attr("src")) }
                .distinctBy { it.login },
            // The page doesn't show the owner's avatar, but GitHub serves every account's at this address.
            ownerAvatarUrl = "https://github.com/$owner.png?size=80",
        )
    }

    private fun String?.toCount(): Int = this?.let { count.find(it)?.value?.replace(",", "")?.toIntOrNull() } ?: 0
}
