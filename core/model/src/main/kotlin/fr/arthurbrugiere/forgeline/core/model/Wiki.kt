package fr.arthurbrugiere.forgeline.core.model

/** A page of a repository's wiki as its list names it: [key] is what its forge asks it by. */
data class WikiPageSummary(val key: String, val title: String)

/** A wiki page to read: its words in Markdown ([body]), and where it is on its forge's site. */
data class WikiPage(val key: String, val title: String, val body: String, val webUrl: String)

/**
 * Where a repository's wiki pages are on its forge's site, each under its key: a page's relative links are read
 * against it, and a link under it opens that page.
 */
val RepoId.wikiBaseUrl: String get() = if (forge.type == ForgeType.GITLAB) "$webUrl/-/wikis/" else "$webUrl/wiki/"

/** Where a wiki's pictures and files are read from, relative to a page: the wiki's own repository. */
val RepoId.wikiRawBaseUrl: String
    get() = when (forge.type) {
        ForgeType.GITHUB -> "https://raw.githubusercontent.com/wiki/$owner/$name/"
        ForgeType.GITLAB -> wikiBaseUrl
        else -> "${wikiBaseUrl}raw/"
    }

/** The page a link to [url] opens in [this] wiki, by its key; null for a link elsewhere, or to the wiki's own lists. */
fun RepoId.wikiPageOf(url: String): String? {
    val key = url.substringBefore('#').substringBefore('?').takeIf { it.startsWith(wikiBaseUrl) }?.removePrefix(wikiBaseUrl)?.trimEnd('/')
    // GitLab lists its pages at "pages".
    return key?.takeIf {
        it.isNotEmpty() && !it.startsWith("_") && it != "raw" && !it.startsWith("raw/") && !it.startsWith("-/") &&
            !(forge.type == ForgeType.GITLAB && it == "pages")
    }
}

/** A page's title from its key, where the forge gives none: "Getting-started" reads "Getting started". */
fun wikiTitleOf(key: String): String = key.substringAfterLast('/').replace('-', ' ')
