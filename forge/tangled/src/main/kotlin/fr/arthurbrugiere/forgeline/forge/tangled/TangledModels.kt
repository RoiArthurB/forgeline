package fr.arthurbrugiere.forgeline.forge.tangled

import kotlinx.serialization.Serializable

// What Tangled's index answers, as its lexicons (tangled.org/tangled.org/core, lexicons/org/tangled) describe them,
// read on 2026-11-02. Only the fields Forgeline shows are kept; a record embedded as `value` is a `sh.tangled.*` one.

/** `org.tangled.actor.defs#profileViewBasic` (and the detailed view, which adds counts). */
@Serializable
internal data class ProfileViewJson(val did: String, val handle: String, val avatar: String? = null)

/** `org.tangled.repo.defs#repoViewBasic`: [did] is the repository's own DID, [slug] its name in addresses. */
@Serializable
internal data class RepoViewJson(
    val did: String,
    val owner: ProfileViewJson,
    val slug: String,
    val createdAt: String? = null,
    val description: String? = null,
    val forkCount: Int? = null,
    val starCount: Int? = null,
    val primaryLanguage: String? = null,
)

/** `org.tangled.actor.getTrending`: at most five people and four repositories, for no period in particular. */
@Serializable
internal data class TrendingJson(val repos: List<RepoViewJson> = emptyList())

/** The `sh.tangled.repo` record a repository is declared by. [repoDid] is what its git content is asked by. */
@Serializable
internal data class RepoRecordJson(
    val name: String? = null,
    val description: String? = null,
    val website: String? = null,
    val topics: List<String> = emptyList(),
    /** Where it was forked or imported from. */
    val source: String? = null,
    val repoDid: String? = null,
    val createdAt: String? = null,
)

/** `org.tangled.temp.repo.getRepoByName`. */
@Serializable
internal data class RepoByNameJson(val uri: String, val value: RepoRecordJson, val knot: String? = null, val git: GitInfoJson? = null)

@Serializable
internal data class GitInfoJson(val defaultBranch: String, val isEmpty: Boolean = false, val languages: List<LanguageJson> = emptyList())

/** Bytes of code in [name]. */
@Serializable
internal data class LanguageJson(val name: String, val size: Long)

/** `org.tangled.temp.git.getTree`: a folder's entries, with git's modes (`0040000` a folder, `0120000` a link, `0160000` a submodule). */
@Serializable
internal data class TreeJson(val entries: List<TreeEntryJson> = emptyList())

@Serializable
internal data class TreeEntryJson(val name: String, val mode: String, val size: Long = 0)

/** `org.tangled.temp.git.listBranches`, and `listTags` with `tags` instead. */
@Serializable
internal data class RefsJson(
    val branches: List<NamedRefJson> = emptyList(),
    val tags: List<NamedRefJson> = emptyList(),
    val cursor: String? = null,
)

@Serializable
internal data class NamedRefJson(val name: String)

/** What every `count…` query answers. */
@Serializable
internal data class CountJson(val count: Int = 0)

/** A list query's page of records: `listStars`, `listRepos`. */
@Serializable
internal data class RecordListJson<T>(val items: List<RecordJson<T>> = emptyList(), val cursor: String? = null, val total: Int? = null)

@Serializable
internal data class RecordJson<T>(val uri: String, val value: T)

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
