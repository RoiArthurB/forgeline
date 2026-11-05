package fr.arthurbrugiere.forgeline.forge.tangled

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.tangled.temp.repo.GetRepoByNameGitInfo

// The records Tangled's index embeds in its answers as `value`. Its lexicons type them `unknown` (the index serves
// records of former and current kinds alike, `sh.tangled.*` and `org.tangled.*`), so the generated client hands them
// over as JSON, read here leniently: only the fields Forgeline shows, each optional.

/**
 * The record a repository is declared by, of either kind the index serves. A former `sh.tangled.repo` record lives in
 * its owner's AT Protocol repository, names the repository by [name] (or its record key) and its own DID by [repoDid].
 * An `org.tangled.repo.manifest` record (all of them since 2026-11) lives in the repository's own, at
 * `at://<repository DID>/org.tangled.repo.manifest/self`, and names the repository and its owner in [declaration]. So
 * what a record means is read with the address it's kept at: [name], [ownerDid] and [repoDid].
 */
@Serializable
internal data class RepoRecordJson(
    val name: String? = null,
    val declaration: DeclarationJson? = null,
    val description: String? = null,
    val website: String? = null,
    val topics: List<String> = emptyList(),
    /** Where it was forked or imported from: an address in former records, an object naming the source in current ones. */
    val source: JsonElement? = null,
    val repoDid: String? = null,
    val createdAt: String? = null,
) {
    /** The repository's name, the record kept at [uri] being this one. */
    fun name(uri: String): String = declaration?.name ?: name ?: uri.recordKey

    /** Its owner's DID. */
    fun ownerDid(uri: String): String = declaration?.owner ?: uri.authority

    /** The DID its git content is asked by; null for a record declared before repositories had their own. */
    fun repoDid(uri: String): String? = repoDid ?: uri.authority.takeIf { uri.collection == MANIFEST }
}

private const val MANIFEST = "org.tangled.repo.manifest"

/** An `org.tangled.repo.manifest`'s `declaration`: the repository's owner and name. */
@Serializable
internal data class DeclarationJson(val owner: String? = null, val name: String? = null)

/** A repository as `org.tangled.temp.repo.getRepoByName` found it: its record, its own DID, and what its git says. */
internal data class FoundRepo(val record: RepoRecordJson, val repoDid: String?, val git: GetRepoByNameGitInfo?)

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
    /** The profile picture, a blob kept on the person's own server. */
    val avatar: BlobJson? = null,
)

/** A blob a record names: by `ref.$link` as records write it now, or by `cid` as early ones did. */
@Serializable
internal data class BlobJson(val ref: BlobRefJson? = null, val cid: String? = null) {
    val link: String? get() = ref?.link ?: cid
}

@Serializable
internal data class BlobRefJson(@kotlinx.serialization.SerialName("\$link") val link: String? = null)

/** What Bluesky's public AppView says of someone: only their picture is read. */
@Serializable
internal data class BlueskyProfileJson(val avatar: String? = null)

/** The last part of an AT URI, `at://did/collection/<rkey>`. */
internal val String.recordKey: String get() = substringAfterLast('/')

/** The DID an AT URI's record is kept under, `at://<did>/collection/rkey`. */
internal val String.authority: String get() = removePrefix("at://").substringBefore('/')

/** The collection an AT URI's record is in, `at://did/<collection>/rkey`. */
internal val String.collection: String get() = removePrefix("at://").substringAfter('/', "").substringBefore('/')
