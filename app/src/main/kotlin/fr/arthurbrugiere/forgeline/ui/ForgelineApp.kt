package fr.arthurbrugiere.forgeline.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import fr.arthurbrugiere.forgeline.inbox.InboxRoute as InboxDestination
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.navigation.AppNavigator
import fr.arthurbrugiere.forgeline.navigation.CreditsRoute as CreditsKey
import fr.arthurbrugiere.forgeline.navigation.FeedRoute
import fr.arthurbrugiere.forgeline.navigation.InboxRoute
import fr.arthurbrugiere.forgeline.navigation.SettingsRoute
import fr.arthurbrugiere.forgeline.navigation.RepoRoute as RepoKey
import fr.arthurbrugiere.forgeline.navigation.FileRoute as FileKey
import fr.arthurbrugiere.forgeline.file.FileRoute as FileDestination
import fr.arthurbrugiere.forgeline.repo.RepoRoute as RepoDestination
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.navigation.IssueRoute as IssueKey
import fr.arthurbrugiere.forgeline.navigation.UserRoute as UserKey
import fr.arthurbrugiere.forgeline.issue.IssueRoute as IssueDestination
import fr.arthurbrugiere.forgeline.user.UserRoute as UserDestination
import fr.arthurbrugiere.forgeline.navigation.SignInRoute as SignInKey
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.signin.SignInRoute
import fr.arthurbrugiere.forgeline.navigation.TopLevelDestination
import fr.arthurbrugiere.forgeline.navigation.TrendingRoute
import fr.arthurbrugiere.forgeline.navigation.YouRoute
import fr.arthurbrugiere.forgeline.navigation.rememberAppNavigator
import fr.arthurbrugiere.forgeline.settings.SettingsRoute as SettingsDestination
import fr.arthurbrugiere.forgeline.trending.TrendingRoute as TrendingDestination
import fr.arthurbrugiere.forgeline.you.YouScreen

@Composable
fun ForgelineApp(
    session: SessionState,
    onSignOut: () -> Unit,
    link: NavKey? = null,
    onLinkOpened: () -> Unit = {},
    navigator: AppNavigator = rememberAppNavigator(),
) {
    LaunchedEffect(link) {
        if (link != null) {
            navigator.openLink(link)
            onLinkOpened()
        }
    }
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
        ForgelineNavDisplay(navigator, session, onSignOut)
    }
}

@Composable
private fun ForgelineNavDisplay(navigator: AppNavigator, session: SessionState, onSignOut: () -> Unit) {
    val signIn = { navigator.navigate(SignInKey) }
    val openRepo = { id: RepoId -> navigator.navigate(RepoKey(id.owner, id.name)) }
    val openIssue = { ref: IssueRef -> navigator.navigate(IssueKey(ref.repo.owner, ref.repo.name, ref.number)) }
    val openUser = { login: String -> navigator.navigate(UserKey(login)) }
    val provider = entryProvider<NavKey> {
        entry<InboxRoute> {
            InboxDestination(
                session = session,
                onSignIn = signIn,
                onOpenThread = { thread ->
                    val number = thread.number
                    if (number != null && (thread.type == SubjectType.ISSUE || thread.type == SubjectType.PULL_REQUEST)) {
                        openIssue(IssueRef(thread.repo, number))
                    } else {
                        openRepo(thread.repo)
                    }
                },
            )
        }
        entry<FeedRoute> { FeedScreen(session, onSignIn = signIn) }
        entry<TrendingRoute> { TrendingDestination(session, onSignIn = signIn, onOpenRepo = openRepo) }
        entry<FileKey> { key ->
            FileDestination(key, onBack = navigator::goBack, onOpenRepo = openRepo, onOpenIssue = openIssue, onOpenUser = openUser)
        }
        entry<IssueKey> { key ->
            IssueDestination(key, onBack = navigator::goBack, onOpenRepo = openRepo, onOpenIssue = openIssue, onOpenUser = openUser)
        }
        entry<UserKey> { key ->
            UserDestination(key, session = session, onBack = navigator::goBack, onOpenRepo = openRepo, onSignIn = signIn)
        }
        entry<RepoKey> { key ->
            RepoDestination(
                route = key,
                session = session,
                onBack = navigator::goBack,
                onOpenRepo = openRepo,
                onOpenFile = { id, path, ref -> navigator.navigate(FileKey(id.owner, id.name, path, ref)) },
                onOpenIssue = openIssue,
                onOpenUser = openUser,
                onSignIn = signIn,
            )
        }
        entry<YouRoute> {
            YouScreen(session, onSignIn = signIn, onOpenSettings = { navigator.navigate(SettingsRoute) }, onOpenProfile = openUser)
        }
        entry<SettingsRoute> {
            SettingsDestination(
                session = session,
                onSignIn = signIn,
                onSignOut = onSignOut,
                onBack = navigator::goBack,
                onOpenCredits = { navigator.navigate(CreditsKey) },
            )
        }
        entry<SignInKey> {
            SignInRoute(onBack = navigator::goBack, onSignedIn = navigator::goBack)
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
