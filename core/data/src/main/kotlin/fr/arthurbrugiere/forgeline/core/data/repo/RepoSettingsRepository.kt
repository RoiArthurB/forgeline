package fr.arthurbrugiere.forgeline.core.data.repo

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.PagesSite
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import javax.inject.Inject
import javax.inject.Singleton

/** What a repository's owner sets on it, read and changed as the account signed in on its forge. */
interface RepoSettingsRepository {
    suspend fun settings(repo: RepoId): ForgeResult<RepoSettings>

    /** Makes [change], and answers the settings as the forge then has them. */
    suspend fun change(repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings>

    /** Gives [repo] another name, and answers where it now is. */
    suspend fun rename(repo: RepoId, name: String): ForgeResult<RepoId>

    /** Deletes [repo]: gone, or kept a while by its forge before it is. */
    suspend fun delete(repo: RepoId): ForgeResult<RepoDeletion>

    /** The website served from [repo]; null where its forge sets nothing about one. */
    suspend fun pages(repo: RepoId): ForgeResult<PagesSite?>

    suspend fun changePages(repo: RepoId, change: PagesChange): ForgeResult<PagesSite>
}

@Singleton
class DefaultRepoSettingsRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val repos: RepoRepository,
) : RepoSettingsRepository {

    override suspend fun settings(repo: RepoId): ForgeResult<RepoSettings> {
        val token = accounts.tokenOn(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.repoSettings(repo.forge).settings(token, repo)
    }

    override suspend fun change(repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings> {
        val token = accounts.tokenOn(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.repoSettings(repo.forge).change(token, repo, change).also {
            // The repository's own page shows some of these (its description, its topics, what it has switched on).
            if (it is ForgeResult.Success) repos.refresh(repo, force = true)
        }
    }

    override suspend fun rename(repo: RepoId, name: String): ForgeResult<RepoId> {
        val token = accounts.tokenOn(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.repoSettings(repo.forge).rename(token, repo, name).also {
            // Read under its new name before its page opens there.
            if (it is ForgeResult.Success) repos.refresh(it.value, force = true)
        }
    }

    override suspend fun delete(repo: RepoId): ForgeResult<RepoDeletion> {
        val token = accounts.tokenOn(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.repoSettings(repo.forge).delete(token, repo)
    }

    override suspend fun pages(repo: RepoId): ForgeResult<PagesSite?> {
        val token = accounts.tokenOn(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.repoSettings(repo.forge).pages(token, repo)
    }

    override suspend fun changePages(repo: RepoId, change: PagesChange): ForgeResult<PagesSite> {
        val token = accounts.tokenOn(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.repoSettings(repo.forge).changePages(token, repo, change)
    }
}
