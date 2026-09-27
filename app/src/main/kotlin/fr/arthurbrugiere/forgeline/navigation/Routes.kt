package fr.arthurbrugiere.forgeline.navigation

import androidx.navigation3.runtime.NavKey
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

@Serializable
data object CreditsRoute : NavKey

@Serializable
data object SignInRoute : NavKey

@Serializable
data class RepoRoute(val owner: String, val name: String) : NavKey

@Serializable
data class FileRoute(val owner: String, val name: String, val path: String, val ref: String) : NavKey

@Serializable
data class IssueRoute(val owner: String, val name: String, val number: Int) : NavKey

@Serializable
data class UserRoute(val login: String) : NavKey
