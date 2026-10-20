package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode

/**
 * One folder of a repository at [ref], from its git tree, named `ref:path` as git names it: 1 to 2 s on Codeberg. A
 * folder's contents listing works out each file's last commit instead, and takes up to 31 s there, or answers 504
 * (2026-10-20). Null when [ref] has no folder at [path], or no files at all.
 */
internal suspend fun HttpClient.forgejoTree(forge: ForgeInstance, token: String?, id: RepoId, ref: String, path: String): ForgeResult<List<TreeJson.Entry>?> {
    val folder = path.trim('/')
    val treeish = if (folder.isEmpty()) ref else "$ref:$folder"
    val entries = mutableListOf<TreeJson.Entry>()
    for (page in 1..MAX_TREE_PAGES) {
        val response = forgejoApi(forge, token, "repos", id.owner, id.name, "git", "trees", treeish, query = mapOf("per_page" to "$TREE_PAGE_SIZE", "page" to "$page"))
        when (response.status) {
            HttpStatusCode.OK -> Unit
            // A tree Forgejo can't find is a 400 ("sha not found"); an empty repository may answer 404.
            HttpStatusCode.BadRequest, HttpStatusCode.NotFound -> return ForgeResult.Success(null)
            else -> return response.failure()
        }
        val tree = response.body<TreeJson>()
        entries += tree.tree
        // Every page says "truncated", the last one too: the total is what tells.
        if (tree.tree.isEmpty() || entries.size >= tree.totalCount) break
    }
    return ForgeResult.Success(entries)
}

/** The most a page of a git tree holds on Forgejo. */
private const val TREE_PAGE_SIZE = 1000

/** Ten thousand entries in one folder is past what anyone browses on a phone. */
private const val MAX_TREE_PAGES = 10
