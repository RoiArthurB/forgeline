package fr.arthurbrugiere.forgeline.forge.tangled

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.tangled.temp.repo.GetRepoByNameGitInfo

// The records Tangled's index embeds in its answers as `value`. Its lexicons type them `unknown` (the index serves
// records of former and current kinds alike, `sh.tangled.*` and `org.tangled.*`), so the generated client hands them
// over as JSON, read here leniently: only the fields Forgeline shows, each optional.

/** The `sh.tangled.repo` record a repository is declared by. [repoDid] is what its git content is asked by. */
@Serializable
internal data class RepoRecordJson(
    val name: String? = null,
    val description: String? = null,
    val website: String? = null,
    val topics: List<String> = emptyList(),
    /** Where it was forked or imported from: an address in former records, an object naming the source in current ones. */
    val source: JsonElement? = null,
    val repoDid: String? = null,
    val createdAt: String? = null,
)

/** A repository as `org.tangled.temp.repo.getRepoByName` found it: its record, and what its git says. */
internal data class FoundRepo(val record: RepoRecordJson, val git: GetRepoByNameGitInfo?)

/** A `sh.tangled.feed.star` record: only when it was given matters. */
@Serializable
internal data class StarRecordJson(val createdAt: String? = null)

/** The `org.tangled.actor.profile` record. Tangled has no display name: people go by their handle. */
@Serializable
internal data class ProfileRecordJson(
    val description: String? = null,
    val location: String? = null,
    val links: List<String> = emptyList(),
    val isOrganization: Boolean = false,
)

/** The last part of an AT URI, `at://did/collection/<rkey>`. */
internal val String.recordKey: String get() = substringAfterLast('/')
