package fr.arthurbrugiere.forgeline.core.markdown

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The languages the bundled TextMate grammars color, and what tells a file of each: its name, a name pattern, its
 * extension, or its first line. All of it comes from the grammars' own index, `textmate/languages.json`, which
 * `tools/textmate/import_grammars.py` writes from the VS Code extensions that ship them; nothing is listed here.
 */
internal class TextMateLanguages(index: JsonObject) {
    class Language(
        val id: String,
        val scopeName: String,
        val aliases: List<String>,
        val extensions: List<String>,
        val fileNames: List<String>,
        val namePatterns: List<Regex>,
        private val firstLineSource: String?,
    ) {
        /** Compiled on first use: most files are told by their name before any first line is read. */
        val firstLine: Regex? by lazy { firstLineSource?.let { runCatching { Regex(it) }.getOrNull() } }
    }

    class Grammar(val scopeName: String, val file: String, val injectTo: List<String>)

    val languages: List<Language> = index.getAsJsonArray("languages").map { it.asJsonObject }.map { language ->
        Language(
            id = language.get("id").asString,
            scopeName = language.get("scopeName").asString,
            aliases = language.strings("aliases"),
            extensions = language.strings("extensions"),
            fileNames = language.strings("filenames"),
            namePatterns = language.strings("filenamePatterns").map(::globToRegex),
            firstLineSource = language.get("firstLine")?.asString,
        )
    }

    val grammars: Map<String, Grammar> = index.getAsJsonArray("grammars").map { it.asJsonObject }.associate { grammar ->
        val scope = grammar.get("scopeName").asString
        scope to Grammar(scope, grammar.get("file").asString, grammar.strings("injectTo"))
    }

    /** The grammars that add to [scopeName]'s, as fenced code inside Markdown. */
    fun injectionsInto(scopeName: String): List<String> = grammars.values.filter { scopeName in it.injectTo }.map { it.scopeName }

    /**
     * The language of the file at [path] (a name, or a path in its repository), [code] telling a script without an
     * extension by its first line. The order is VS Code's: the exact name, a name pattern, the longest extension, then
     * the first line.
     */
    fun forFile(path: String, code: String? = null): Language? {
        val name = path.substringAfterLast('/')
        languages.firstOrNull { name in it.fileNames }?.let { return it }
        languages.firstOrNull { language -> language.fileNames.any { it.equals(name, ignoreCase = true) } }?.let { return it }
        languages.firstOrNull { language -> language.namePatterns.any { it.matches(path) || it.matches(name) } }?.let { return it }
        val lowerName = name.lowercase()
        languages
            .mapNotNull { language -> language.extensions.filter { lowerName.endsWith(it.lowercase()) }.maxOfOrNull { it.length }?.let { language to it } }
            .maxByOrNull { it.second }
            ?.let { return it.first }
        val firstLine = code?.substringBefore('\n')?.takeIf { it.isNotBlank() } ?: return null
        return languages.firstOrNull { it.firstLine?.containsMatchIn(firstLine) == true }
    }

    /** The language a Markdown code fence names, as ```kotlin, ```js or ```kt: by its id, one of its names, or an extension. */
    fun forFence(info: String): Language? {
        val name = info.trim().substringBefore(' ').substringBefore('{').trim().takeIf { it.isNotEmpty() } ?: return null
        return languages.firstOrNull { it.id.equals(name, ignoreCase = true) }
            ?: languages.firstOrNull { language -> language.aliases.any { it.equals(name, ignoreCase = true) } }
            ?: languages.firstOrNull { language -> language.extensions.any { it.equals(".$name", ignoreCase = true) } }
    }

    companion object {
        private const val INDEX = "/textmate/languages.json"

        /** The index the app ships, read once. */
        val bundled: TextMateLanguages by lazy {
            val stream = checkNotNull(TextMateLanguages::class.java.getResourceAsStream(INDEX)) { "$INDEX is missing: run tools/textmate/import_grammars.py" }
            TextMateLanguages(stream.reader().use { JsonParser.parseReader(it).asJsonObject })
        }

        private fun JsonObject.strings(key: String): List<String> = (get(key) as? JsonArray)?.map { it.asString }.orEmpty()

        /** A VS Code file pattern (`**` any folders, `*` and `?` within a name, `{a,b}` either) as a regular expression. */
        internal fun globToRegex(glob: String): Regex {
            val pattern = StringBuilder()
            var i = 0
            var braces = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    glob.startsWith("**/", i) -> {
                        pattern.append("(?:.*/)?")
                        i += 2
                    }
                    glob.startsWith("**", i) -> {
                        pattern.append(".*")
                        i += 1
                    }
                    c == '*' -> pattern.append("[^/]*")
                    c == '?' -> pattern.append("[^/]")
                    c == '{' -> pattern.append("(?:").also { braces++ }
                    c == '}' && braces > 0 -> pattern.append(")").also { braces-- }
                    c == ',' && braces > 0 -> pattern.append("|")
                    else -> pattern.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(pattern.toString(), RegexOption.IGNORE_CASE)
        }
    }
}
