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
