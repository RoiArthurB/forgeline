package fr.arthurbrugiere.forgeline.core.markdown

/**
 * Where a README's relative paths point. Forge-specific: GitHub serves raw files from
 * raw.githubusercontent.com and pages from /blob/, Forgejo uses /raw/ and /src/.
 */
data class ReadmeContext(
    /** Base for images, ending with `/`, e.g. `https://raw.githubusercontent.com/o/r/main/`. */
    val rawBaseUrl: String,
    /** Base for links, ending with `/`, e.g. `https://github.com/o/r/blob/main/`. */
    val blobBaseUrl: String,
    /** Folder of the README inside the repo, ending with `/` (empty at the root). */
    val directory: String = "",
)

/**
 * Turns a forge README into Markdown the renderer understands: converts the HTML READMEs are
 * full of (centered badges, `<picture>` theme variants, `<details>`...) and resolves relative
 * paths. Code blocks and inline code are never modified.
 */
object ReadmePreprocessor {

    fun prepare(markdown: String, context: ReadmeContext, darkTheme: Boolean): String =
        splitCode(markdown).joinToString("") { segment ->
            if (segment.isCode) segment.text else convertProse(segment.text, context, darkTheme)
        }

    private data class Segment(val text: String, val isCode: Boolean)

    private val fence = Regex("""^ {0,3}(```|~~~)""")
    private val inlineCode = Regex("""(`+)(?:(?!\1).)+?\1(?!`)""")

    /** Fenced blocks first (line by line), then inline code spans inside the remaining prose. */
    private fun splitCode(markdown: String): List<Segment> {
        val blocks = mutableListOf<Segment>()
        val current = StringBuilder()
        var inFence: String? = null
        for (line in markdown.split('\n').let { lines -> lines.mapIndexed { i, l -> if (i < lines.lastIndex) "$l\n" else l } }) {
            val marker = fence.find(line)?.groupValues?.get(1)
            when {
                inFence == null && marker != null -> {
                    if (current.isNotEmpty()) blocks += Segment(current.toString(), false).also { current.clear() }
                    inFence = marker
                    current.append(line)
                }
                inFence != null -> {
                    current.append(line)
                    if (marker == inFence) {
                        blocks += Segment(current.toString(), true).also { current.clear() }
                        inFence = null
                    }
                }
                else -> current.append(line)
            }
        }
        if (current.isNotEmpty()) blocks += Segment(current.toString(), inFence != null)

        return blocks.flatMap { block ->
            if (block.isCode) {
                listOf(block)
            } else {
                buildList {
                    var last = 0
                    for (match in inlineCode.findAll(block.text)) {
                        if (match.range.first > last) add(Segment(block.text.substring(last, match.range.first), false))
                        add(Segment(match.value, true))
                        last = match.range.last + 1
                    }
                    if (last < block.text.length) add(Segment(block.text.substring(last), false))
                }
            }
        }
    }

    private val comment = Regex("""<!--[\s\S]*?-->""")
    private val picture = Regex("""<picture\b[^>]*>([\s\S]*?)</picture>""", RegexOption.IGNORE_CASE)
    private val source = Regex("""<source\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val img = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val video = Regex("""<video\b([^>]*)>([\s\S]*?)</video>|<video\b([^>]*)/>""", RegexOption.IGNORE_CASE)
    private val anchor = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val heading = Regex("""<h([1-6])\b[^>]*>([\s\S]*?)</h\1>""", RegexOption.IGNORE_CASE)
    private val lineBreak = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val bold = Regex("""<(b|strong)\b[^>]*>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
    private val italic = Regex("""<(i|em)\b[^>]*>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
    private val code = Regex("""<(code|kbd)\b[^>]*>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
    private val summary = Regex("""<summary\b[^>]*>([\s\S]*?)</summary>""", RegexOption.IGNORE_CASE)
    private val blockTag = Regex("""</?(p|div|center|details|table|tr|td|th|tbody|thead)\b[^>]*>""", RegexOption.IGNORE_CASE)

    // A letter-led name followed by whitespace, `/` or `>`: excludes autolinks like <https://x> and <me@x.dev>.
    private val anyTag = Regex("""</?[a-zA-Z][a-zA-Z0-9-]*(?:\s[^>]*)?/?>""")
    private val htmlLine = Regex("""(?m)^[ \t]+(?=<)""")
    private val blankLine = Regex("""(?m)^[ \t]+$""")
    private val entity = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")

    // Escaping entities (&lt; &gt; &amp;) stay encoded so decoded text can never form a tag.
    private val namedEntities = mapOf(
        "nbsp" to "\u00A0", "middot" to "·", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "copy" to "©", "reg" to "®", "trade" to "™", "laquo" to "«", "raquo" to "»",
        "rarr" to "→", "larr" to "←", "uarr" to "↑", "darr" to "↓", "bull" to "•", "quot" to "\"", "apos" to "'",
    )
    private val extraBlankLines = Regex("""\n{3,}""")

    private fun convertProse(text: String, context: ReadmeContext, darkTheme: Boolean): String {
        if ('<' !in text && '[' !in text && '&' !in text) return text
        var out = text
        if ('<' in out) {
            out = out.replace(comment, "")
            // Un-indent HTML lines first: once converted, 4-space indents would read as code blocks.
            out = out.replace(htmlLine, "")
            out = out.replace(picture) { pictureToMarkdown(it.groupValues[1], darkTheme, context) }
            out = out.replace(img) { imgToMarkdown(it.value, context) }
            out = out.replace(video) { videoToMarkdown(it.value, context) }
            out = out.replace(anchor) { match ->
                val href = attribute(match.groupValues[1], "href")
                val inner = match.groupValues[2].trim()
                if (href == null) inner else "[$inner](${resolveLink(href, context)})"
            }
            out = out.replace(heading) { "#".repeat(it.groupValues[1].toInt()) + " " + it.groupValues[2].trim() }
            out = out.replace(lineBreak, "  \n")
            out = out.replace(bold) { "**${it.groupValues[2]}**" }
            out = out.replace(italic) { "*${it.groupValues[2]}*" }
            out = out.replace(code) { "`${it.groupValues[2]}`" }
            out = out.replace(summary) { "**${it.groupValues[1].trim()}**" }
            out = out.replace(blockTag, "\n\n")
            out = out.replace(anyTag, "")
            out = out.replace(blankLine, "")
            out = out.replace(extraBlankLines, "\n\n")
            if (text.none { it == '\n' }) out = out.trim()
        }
        if ('&' in out) out = out.replace(entity) { decodeTextEntity(it) }
        return rewriteMarkdownUrls(out, context)
    }

    private fun decodeTextEntity(match: MatchResult): String {
        val name = match.groupValues[1]
        val codePoint = when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)
            name.startsWith("#") -> name.drop(1).toIntOrNull()
            else -> return namedEntities[name] ?: match.value
        }
        // Numeric forms of <, > and & stay encoded, like their named forms.
        if (codePoint == null || codePoint in setOf(0x3C, 0x3E, 0x26) || !Character.isValidCodePoint(codePoint)) return match.value
        return String(Character.toChars(codePoint))
    }

    private fun videoToMarkdown(tag: String, context: ReadmeContext): String {
        val src = attribute(tag.substringBefore('>'), "src") ?: source.find(tag)?.let { attribute(it.value, "src") } ?: return ""
        return "[▶ Watch video](${resolveLink(src, context)})"
    }

    private fun pictureToMarkdown(inner: String, darkTheme: Boolean, context: ReadmeContext): String {
        val fallback = img.find(inner)?.value
        val wanted = if (darkTheme) "dark" else "light"
        val themed = source.findAll(inner)
            .firstOrNull { attribute(it.value, "media")?.contains(wanted, ignoreCase = true) == true }
            ?.let { attribute(it.value, "srcset")?.substringBefore(' ') }
        val alt = fallback?.let { attribute(it, "alt") }.orEmpty()
        val src = themed ?: fallback?.let { attribute(it, "src") } ?: return ""
        return "![$alt](${resolveImage(src, context)})"
    }

    private fun imgToMarkdown(tag: String, context: ReadmeContext): String {
        val src = attribute(tag, "src") ?: return ""
        return "![${attribute(tag, "alt").orEmpty()}](${resolveImage(src, context)})"
    }

    private fun attribute(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            ?.let(::decodeEntities)

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private val markdownImage = Regex("""!\[([^\]]*)]\(\s*([^)\s]+)(\s+"[^"]*")?\s*\)""")

    // Link text may itself contain an image: [![alt](src)](href).
    private val markdownLink = Regex("""(?<!!)\[((?:!\[[^\]]*]\([^)]*\)|[^\]])*)]\(\s*([^)\s]+)(\s+"[^"]*")?\s*\)""")
    private val referenceDefinition = Regex("""(?m)^( {0,3}\[[^\]]+]:\s*)(\S+)""")
    private val imageExtension = Regex("""\.(png|jpe?g|gif|svg|webp|avif)(\?.*)?$""", RegexOption.IGNORE_CASE)

    private fun rewriteMarkdownUrls(text: String, context: ReadmeContext): String {
        if ("](" !in text && "]:" !in text) return text
        return text
            .replace(markdownImage) { "![${it.groupValues[1]}](${resolveImage(it.groupValues[2], context)}${it.groupValues[3]})" }
            .replace(markdownLink) { "[${it.groupValues[1]}](${resolveLink(it.groupValues[2], context)}${it.groupValues[3]})" }
            .replace(referenceDefinition) {
                val url = it.groupValues[2]
                val resolved = if (imageExtension.containsMatchIn(url)) resolveImage(url, context) else resolveLink(url, context)
                it.groupValues[1] + resolved
            }
    }

    private val scheme = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")
    private val gitHubBlob = Regex("""^https://github\.com/([^/]+)/([^/]+)/blob/(.+?)(\?raw=true)?$""")

    private fun resolveImage(url: String, context: ReadmeContext): String {
        gitHubBlob.find(url)?.let { match ->
            val (owner, repo, path) = match.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$path"
        }
        return resolve(url, context.rawBaseUrl, context)
    }

    private fun resolveLink(url: String, context: ReadmeContext): String = resolve(url, context.blobBaseUrl, context)

    private fun resolve(url: String, base: String, context: ReadmeContext): String {
        if (scheme.containsMatchIn(url) || url.startsWith("#") || url.startsWith("//")) return url
        val path = if (url.startsWith("/")) url.trimStart('/') else normalize(context.directory + url)
        return base + path
    }

    private fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/") + if (path.endsWith("/")) "/" else ""
    }
}
