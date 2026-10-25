package fr.arthurbrugiere.forgeline.core.model

/** A file a run kept, as its forge lists it: [sizeBytes] is its archive's size, and an [expired] one can't be had any more. */
data class RunArtifact(val id: Long, val name: String, val sizeBytes: Long, val expired: Boolean)

/** A Markdown file found in an artifact: where it is in it, and its words. */
data class ArtifactDocument(val path: String, val markdown: String)
