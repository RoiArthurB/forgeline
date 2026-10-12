package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange

/** What a repository's owner sets on its forge. Every call needs a token, and the forge wants its bearer to administer the repository. */
interface RepoSettingsApi {
    suspend fun settings(token: String, repo: RepoId): ForgeResult<RepoSettings>

    /** Makes [change], and answers the settings as the forge then has them. */
    suspend fun change(token: String, repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings>

    /** Gives [repo] another name under the same owner, and answers where it now is. */
    suspend fun rename(token: String, repo: RepoId, name: String): ForgeResult<RepoId>

    /**
     * Deletes [repo], for good or after the while its forge keeps it. Unauthorized when the sign-in may not delete
     * (GitHub asks for a permission of its own), which signing in again gives.
     */
    suspend fun delete(token: String, repo: RepoId): ForgeResult<RepoDeletion>
}
