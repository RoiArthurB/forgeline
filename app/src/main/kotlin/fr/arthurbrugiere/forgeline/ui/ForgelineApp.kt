package fr.arthurbrugiere.forgeline.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import fr.arthurbrugiere.forgeline.credits.CreditsRoute
import fr.arthurbrugiere.forgeline.feed.FeedScreen
import fr.arthurbrugiere.forgeline.inbox.InboxScreen
import fr.arthurbrugiere.forgeline.navigation.AppNavigator
import fr.arthurbrugiere.forgeline.navigation.CreditsRoute as CreditsKey
import fr.arthurbrugiere.forgeline.navigation.FeedRoute
import fr.arthurbrugiere.forgeline.navigation.InboxRoute
import fr.arthurbrugiere.forgeline.navigation.SettingsRoute
import fr.arthurbrugiere.forgeline.navigation.TopLevelDestination
import fr.arthurbrugiere.forgeline.navigation.TrendingRoute
import fr.arthurbrugiere.forgeline.navigation.YouRoute
import fr.arthurbrugiere.forgeline.navigation.rememberAppNavigator
import fr.arthurbrugiere.forgeline.settings.SettingsRoute as SettingsDestination
import fr.arthurbrugiere.forgeline.trending.TrendingScreen
import fr.arthurbrugiere.forgeline.you.YouScreen

@Composable
fun ForgelineApp(navigator: AppNavigator = rememberAppNavigator()) {
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == navigator.currentTab
                item(
                    selected = selected,
                    onClick = { navigator.selectTab(destination) },
                    icon = {
                        Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null)
                    },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
    ) {
        ForgelineNavDisplay(navigator)
    }
}

@Composable
private fun ForgelineNavDisplay(navigator: AppNavigator) {
    val provider = entryProvider<NavKey> {
        entry<InboxRoute> { InboxScreen() }
        entry<FeedRoute> { FeedScreen() }
        entry<TrendingRoute> { TrendingScreen() }
        entry<YouRoute> { YouScreen(onOpenSettings = { navigator.navigate(SettingsRoute) }) }
        entry<SettingsRoute> {
            SettingsDestination(onBack = navigator::goBack, onOpenCredits = { navigator.navigate(CreditsKey) })
        }
        entry<CreditsKey> {
            CreditsRoute(onBack = navigator::goBack)
        }
    }
    val entriesByTab = TopLevelDestination.entries.associateWith { tab ->
        rememberDecoratedNavEntries(
            backStack = navigator.backStackOf(tab),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            // Scope content keys by tab so the same screen can live in two tabs at once.
            entryProvider = { key ->
                val entry = provider(key)
                NavEntry(key, contentKey = "${tab.name}/${entry.contentKey}", metadata = entry.metadata) {
                    entry.Content()
                }
            },
        )
    }
    NavDisplay(
        entries = navigator.visibleBackStacks().flatMap { entriesByTab.getValue(it) },
        onBack = navigator::goBack,
    )
}
