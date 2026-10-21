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

    /** How long a rename takes to be answered, as a real forge's does: what waits for it must wait for its answer. */
    var renameDelayMillis: Long = 0

    /** How long a change takes to be answered, off the caller's thread as a real forge's is; recorded once it is. */
    var writeLatencyMillis: Long = 0

    override suspend fun settings(token: String, repo: RepoId): ForgeResult<RepoSettings> {
        calls += "settings:${repo.fullName}"
        return failure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(settings)
    }

    override suspend fun change(token: String, repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings> {
        answerLate(writeLatencyMillis)
        calls += "change:${repo.fullName}"
        changes += change
        writeFailure?.let { return ForgeResult.Failure(it) }
        settings = settings.with(change)
        return ForgeResult.Success(settings)
    }

    /** Whether the sign-in may delete repositories at all. */
    var mayDelete = true

    override suspend fun mayDelete(token: String): ForgeResult<Boolean> {
        calls += "mayDelete"
        return failure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(mayDelete)
    }

    /** What deleting answers with. */
    var deletion = fr.arthurbrugiere.forgeline.core.model.RepoDeletion.DELETED
    val renamed = mutableListOf<String>()
    val deleted = mutableListOf<RepoId>()

    override suspend fun rename(token: String, repo: RepoId, name: String): ForgeResult<RepoId> {
        calls += "rename:${repo.fullName}:$name"
        if (renameDelayMillis > 0) kotlinx.coroutines.delay(renameDelayMillis)
        writeFailure?.let { return ForgeResult.Failure(it) }
        renamed += name
        return ForgeResult.Success(RepoId(repo.owner, name, repo.forge))
    }

    override suspend fun delete(token: String, repo: RepoId): ForgeResult<fr.arthurbrugiere.forgeline.core.model.RepoDeletion> {
        calls += "delete:${repo.fullName}"
        writeFailure?.let { return ForgeResult.Failure(it) }
        deleted += repo
        return ForgeResult.Success(deletion)
    }

    /** The repository's website; null for a forge that sets nothing about one. */
    var pages: fr.arthurbrugiere.forgeline.core.model.PagesSite? = fr.arthurbrugiere.forgeline.core.model.PagesSite(published = false, canPublish = true)
    val pagesChanges = mutableListOf<fr.arthurbrugiere.forgeline.core.model.PagesChange>()

    /** How long the site is in coming, off the caller's thread: it is read beside the settings, and may come after them. */
    var pagesReadLatencyMillis: Long = 0

    override suspend fun pages(token: String, repo: RepoId): ForgeResult<fr.arthurbrugiere.forgeline.core.model.PagesSite?> {
        answerLate(pagesReadLatencyMillis)
        calls += "pages:${repo.fullName}"
        return failure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(pages)
    }

    override suspend fun changePages(token: String, repo: RepoId, change: fr.arthurbrugiere.forgeline.core.model.PagesChange): ForgeResult<fr.arthurbrugiere.forgeline.core.model.PagesSite> {
        answerLate(writeLatencyMillis)
        calls += "changePages:${repo.fullName}"
        pagesChanges += change
        writeFailure?.let { return ForgeResult.Failure(it) }
        val before = pages ?: return ForgeResult.Failure(ForgeError.Unsupported)
        val after = when (change) {
            is fr.arthurbrugiere.forgeline.core.model.PagesChange.Publish ->
                before.copy(published = true, build = change.build, url = before.url ?: "https://${repo.owner}.example/${repo.name}/", httpsEnforced = before.httpsEnforced ?: false, customDomain = before.customDomain ?: "")
            is fr.arthurbrugiere.forgeline.core.model.PagesChange.Https -> before.copy(httpsEnforced = change.enforced)
            is fr.arthurbrugiere.forgeline.core.model.PagesChange.Domain -> before.copy(customDomain = change.domain)
            fr.arthurbrugiere.forgeline.core.model.PagesChange.Unpublish -> fr.arthurbrugiere.forgeline.core.model.PagesSite(published = false, canPublish = before.canPublish)
        }
        pages = after
        return ForgeResult.Success(after)
    }
}
