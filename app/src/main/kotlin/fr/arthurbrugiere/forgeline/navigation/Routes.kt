package fr.arthurbrugiere.forgeline.navigation

import androidx.navigation3.runtime.NavKey
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.serialization.Serializable

@Serializable
data object InboxRoute : NavKey

@Serializable
data object FeedRoute : NavKey

@Serializable
data object TrendingRoute : NavKey

@Serializable
data object YouRoute : NavKey

@Serializable
data object SettingsRoute : NavKey

/** One page of Settings. */
@Serializable
data class SettingsSectionRoute(val section: fr.arthurbrugiere.forgeline.settings.SettingsSection) : NavKey

@Serializable
data object CreditsRoute : NavKey

@Serializable
data object SignInRoute : NavKey

// Every route into forge content names its forge by host: the same owner/name can exist on two forges.

@Serializable
data class RepoRoute(val host: String, val owner: String, val name: String) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))
}

@Serializable
data class FileRoute(val host: String, val owner: String, val name: String, val path: String, val ref: String) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))
}

@Serializable
data class IssueRoute(val host: String, val owner: String, val name: String, val number: Int) : NavKey {
    val issue: IssueRef get() = IssueRef(RepoId(owner, name, ForgeInstance.of(host)), number)
}

@Serializable
data class RunRoute(val host: String, val owner: String, val name: String, val runId: Long) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))
}

@Serializable
data class JobLogRoute(val host: String, val owner: String, val name: String, val runId: Long, val jobId: Long, val jobName: String) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))
}

@Serializable
data class UserRoute(val host: String, val login: String) : NavKey {
    val forge: ForgeInstance get() = ForgeInstance.of(host)
}

@Serializable
data object SearchRoute : NavKey

fun RepoId.route() = RepoRoute(forge.host, owner, name)

fun IssueRef.route() = IssueRoute(repo.forge.host, repo.owner, repo.name, number)

fun ForgeInstance.userRoute(login: String) = UserRoute(host, login)
