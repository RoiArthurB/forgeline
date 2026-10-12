package fr.arthurbrugiere.forgeline.core.data.repo

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
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
}
