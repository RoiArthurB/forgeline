package fr.arthurbrugiere.forgeline.forge.sourcehut

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// What git.sr.ht's GraphQL API answers, as its schema (api/graph/schema.graphqls) describes it, read on 2026-11-02. Each
// query asks only for what it shows, so every field here is optional but the ones every answer carries.

/** `{ user(username) { … } }`: null when there's no such person. */
@Serializable
internal data class UserData(val user: UserJson? = null)

/** `{ me { … } }`. */
@Serializable
internal data class MeData(val me: UserJson)

@Serializable
internal data class UserJson(
    /** `~name`, as its addresses write it; asked only where the person is shown. */
    val canonicalName: String? = null,
    val username: String? = null,
    val url: String? = null,
    val location: String? = null,
    val bio: String? = null,
    val created: String? = null,
    val repository: RepositoryJson? = null,
    val repositories: RepositoryCursorJson? = null,
)

@Serializable
internal data class RepositoryCursorJson(val results: List<RepositoryJson> = emptyList(), val cursor: String? = null)

/** A repository, with what one query or another asks of it. */
@Serializable
internal data class RepositoryJson(
    val name: String? = null,
    val description: String? = null,
    /** PUBLIC, UNLISTED or PRIVATE. */
    val visibility: String? = null,
    val created: String? = null,
    val updated: String? = null,
    /** The default branch, as `refs/heads/<name>`; null for a repository nothing was pushed to. */
    @SerialName("HEAD") val head: ReferenceJson? = null,
    @SerialName("revparse_single") val commit: CommitJson? = null,
    val path: TreeEntryJson? = null,
    val references: ReferenceCursorJson? = null,
    val reference: ReferenceJson? = null,
)

@Serializable
internal data class ReferenceCursorJson(val results: List<ReferenceJson> = emptyList(), val cursor: String? = null)

/** A ref (`refs/heads/main`, `refs/tags/v1.0`) and, for a release, what it points at and the files attached to it. */
@Serializable
internal data class ReferenceJson(
    val name: String,
    val follow: ObjectJson? = null,
    val artifacts: ArtifactCursorJson? = null,
)

@Serializable
internal data class ArtifactCursorJson(val results: List<ArtifactJson> = emptyList())

@Serializable
internal data class ArtifactJson(val filename: String, val size: Long = 0, val url: String)

@Serializable
internal data class CommitJson(val tree: TreeJson? = null, val committer: SignatureJson? = null)

/** A git object, read by its `type`: a tree's entries, a blob's text, a tag's message. */
@Serializable
internal data class ObjectJson(
    /** COMMIT, TREE, BLOB or TAG. */
    val type: String? = null,
    val entries: TreeEntryCursorJson? = null,
    val size: Long? = null,
    /** Up to 128 KiB of a text blob; [content] has it all. */
    val text: String? = null,
    val content: String? = null,
    val message: String? = null,
    val tagger: SignatureJson? = null,
    val committer: SignatureJson? = null,
)

@Serializable
internal data class TreeJson(val entries: TreeEntryCursorJson? = null)

@Serializable
internal data class TreeEntryCursorJson(val results: List<TreeEntryJson> = emptyList(), val cursor: String? = null)

/** An entry of a tree. [mode] is git's, as a number: 0o40000 a folder, 0o120000 a link, 0o160000 a submodule. */
@Serializable
internal data class TreeEntryJson(val name: String? = null, val mode: Int = 0, val `object`: ObjectJson? = null)

@Serializable
internal data class SignatureJson(val name: String? = null, val time: String? = null)
