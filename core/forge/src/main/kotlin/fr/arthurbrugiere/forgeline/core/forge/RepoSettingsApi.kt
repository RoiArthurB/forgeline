package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.PagesSite
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

    /**
     * Whether this sign-in may delete repositories at all, known before anyone is asked to confirm: false only where
     * the forge says the sign-in lacks the permission (GitHub's own scope for it); true where it has it or can't say.
     */
    suspend fun mayDelete(token: String): ForgeResult<Boolean> = ForgeResult.Success(true)

    /** The website served from [repo]; null where the forge sets nothing about one through its API (Forgejo). */
    suspend fun pages(token: String, repo: RepoId): ForgeResult<PagesSite?> = ForgeResult.Success(null)

    /** Makes [change] to [repo]'s website, and answers the site as the forge then has it. */
    suspend fun changePages(token: String, repo: RepoId, change: PagesChange): ForgeResult<PagesSite> = ForgeResult.Failure(ForgeError.Unsupported)
}
