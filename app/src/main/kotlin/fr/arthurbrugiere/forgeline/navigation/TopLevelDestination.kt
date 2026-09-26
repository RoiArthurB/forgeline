package fr.arthurbrugiere.forgeline.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import fr.arthurbrugiere.forgeline.R

enum class TopLevelDestination(
    val root: NavKey,
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    INBOX(InboxRoute, R.string.tab_inbox, Icons.Outlined.Inbox, Icons.Filled.Inbox),
    FEED(FeedRoute, R.string.tab_feed, Icons.Outlined.DynamicFeed, Icons.Filled.DynamicFeed),
    TRENDING(TrendingRoute, R.string.tab_trending, Icons.AutoMirrored.Outlined.TrendingUp, Icons.AutoMirrored.Filled.TrendingUp),
    YOU(YouRoute, R.string.tab_you, Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
}
