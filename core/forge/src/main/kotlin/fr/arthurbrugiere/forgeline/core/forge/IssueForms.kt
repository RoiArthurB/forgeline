package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.ContactLink
import fr.arthurbrugiere.forgeline.core.model.IssueChooser
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * One field of an issue form, the YAML templates GitHub and Forgejo read from a repository: what it asks ([label],
 * [description]), what it starts with ([value]), what can be picked in it ([options]), and whether the issue it writes
 * shows it ([inIssue]; a form's own words are shown beside the form only, unless it says otherwise).
 */
data class FormField(
    val type: String,
    val label: String? = null,
    val description: String? = null,
    val value: String? = null,
    val options: List<String> = emptyList(),
    val inIssue: Boolean = type != "markdown",
)

/**
 * What an issue form writes, as a description to fill in: under a heading per field, what it asks as a comment, then
 * its options to tick or keep one of, or what it starts with.
 */
fun issueFormText(fields: List<FormField>): String = fields
    .filter { it.inIssue }
    .joinToString("\n") { field ->
        if (field.type == "markdown") return@joinToString field.value.orEmpty().trimEnd() + "\n"
        buildString {
            append("### ").append(field.label.orEmpty().trim()).append("\n\n")
            field.description?.trim()?.takeIf { it.isNotEmpty() }?.let { append("<!-- ").append(it.replace("-->", "→")).append(" -->\n") }
            when (field.type) {
                "checkboxes" -> field.options.forEach { append("- [ ] ").append(it).append("\n") }
                "dropdown" -> field.options.forEach { append("- ").append(it).append("\n") }
                // An empty answer is the blank line under its heading.
                else -> field.value?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n") }
            }
        }
    }

/**
 * A GitHub issue form, the file [key] of `.github/ISSUE_TEMPLATE` written in YAML, as a template whose body is what the
 * form writes ([issueFormText]); null where [yaml] isn't a form: it doesn't read, or has no name or no fields.
 */
fun parseIssueForm(key: String, yaml: String): IssueTemplate? {
    val form = runCatching { Load(LoadSettings.builder().build()).loadFromString(yaml) }.getOrNull() as? Map<*, *> ?: return null
    val name = form["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val body = form["body"] as? List<*> ?: return null
    val fields = body.mapNotNull { element ->
        val field = element as? Map<*, *> ?: return@mapNotNull null
        val type = field["type"]?.toString() ?: return@mapNotNull null
        val attributes = field["attributes"] as? Map<*, *> ?: emptyMap<Any, Any>()
        // A dropdown's options are words, checkboxes' each a label of its own.
        val options = (attributes["options"] as? List<*>).orEmpty().mapNotNull { option -> ((option as? Map<*, *>)?.get("label") ?: option)?.toString() }
        FormField(type, attributes["label"]?.toString(), attributes["description"]?.toString(), attributes["value"]?.toString(), options)
    }
    if (fields.isEmpty()) return null
    // Labels come as a list, or as one line split by commas.
    val labels = when (val said = form["labels"]) {
        is List<*> -> said.mapNotNull { it?.toString() }
        is String -> said.split(',')
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }
    return IssueTemplate(key, name, form["description"]?.toString()?.trim()?.ifBlank { null }, form["title"]?.toString().orEmpty(), issueFormText(fields), labels)
}

/**
 * GitHub's `.github/ISSUE_TEMPLATE/config.yml`: blank issues are allowed unless it says otherwise, and a link without a
 * name or an address is left out. YAML that doesn't read says nothing: everything is allowed.
 */
fun parseIssueChooser(yaml: String): IssueChooser {
    val config = runCatching { Load(LoadSettings.builder().build()).loadFromString(yaml) }.getOrNull() as? Map<*, *> ?: return IssueChooser()
    val links = (config["contact_links"] as? List<*>).orEmpty().mapNotNull { element ->
        val link = element as? Map<*, *> ?: return@mapNotNull null
        val name = link["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val url = link["url"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ContactLink(name, url, link["about"]?.toString()?.trim()?.ifBlank { null })
    }
    return IssueChooser(blankIssues = config["blank_issues_enabled"] != false, links = links)
}
