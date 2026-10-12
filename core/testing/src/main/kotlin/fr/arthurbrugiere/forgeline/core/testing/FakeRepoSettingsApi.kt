package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.model.with

/** Keeps one repository's [settings] and makes each change to them; records every call as "method:owner/name". */
class FakeRepoSettingsApi(
    var settings: RepoSettings = RepoSettings(
        description = "Tools for octocats",
        website = "https://tools.example",
        topics = listOf("kotlin", "tools"),
        defaultBranch = "main",
        switches = linkedMapOf(RepoSwitch.ISSUES to true, RepoSwitch.WIKI to false, RepoSwitch.MERGE_COMMIT to true, RepoSwitch.SQUASH_MERGE to true),
    ),
) : RepoSettingsApi {
    val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val changes: MutableList<RepoSettingsChange> = java.util.concurrent.CopyOnWriteArrayList()

    /** When set, reading fails with it. */
    var failure: ForgeError? = null

    /** When set, every change fails with it and nothing changes. */
    var writeFailure: ForgeError? = null

    override suspend fun settings(token: String, repo: RepoId): ForgeResult<RepoSettings> {
        calls += "settings:${repo.fullName}"
        return failure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(settings)
    }

    override suspend fun change(token: String, repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings> {
        calls += "change:${repo.fullName}"
        changes += change
        writeFailure?.let { return ForgeResult.Failure(it) }
        settings = settings.with(change)
        return ForgeResult.Success(settings)
    }
}
