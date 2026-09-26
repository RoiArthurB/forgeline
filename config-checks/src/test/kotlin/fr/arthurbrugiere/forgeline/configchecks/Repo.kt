package fr.arthurbrugiere.forgeline.configchecks

import java.io.File

internal object Repo {
    val root: File = File(requireNotNull(System.getProperty("forgeline.repoRoot")) { "run through Gradle" })

    fun file(path: String): File = File(root, path).also { require(it.isFile) { "missing $path" } }

    fun text(path: String): String = file(path).readText()

    fun sources(glob: Regex): Sequence<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" }
        .filter { it.isFile && glob.containsMatchIn(it.relativeTo(root).invariantSeparatorsPath) }
}
