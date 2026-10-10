package fr.arthurbrugiere.forgeline.ui

import fr.arthurbrugiere.forgeline.core.model.StartTab
import fr.arthurbrugiere.forgeline.navigation.DiscussionRoute
import fr.arthurbrugiere.forgeline.R
import androidx.compose.ui.res.stringResource
import fr.arthurbrugiere.forgeline.navigation.PictureRoute
import fr.arthurbrugiere.forgeline.core.markdown.LocalOpenPictureLabel
import fr.arthurbrugiere.forgeline.core.markdown.LocalOpenPicture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import fr.arthurbrugiere.forgeline.credits.CreditsRoute
import fr.arthurbrugiere.forgeline.feed.FeedRoute as FeedDestination
import fr.arthurbrugiere.forgeline.inbox.InboxRoute as InboxDestination
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.navigation.AppNavigator
import fr.arthurbrugiere.forgeline.navigation.CreditsRoute as CreditsKey
import fr.arthurbrugiere.forgeline.navigation.FeedRoute
import fr.arthurbrugiere.forgeline.navigation.InboxRoute
import fr.arthurbrugiere.forgeline.navigation.SettingsRoute
import fr.arthurbrugiere.forgeline.navigation.WorkRoute
import fr.arthurbrugiere.forgeline.navigation.SettingsSectionRoute
import fr.arthurbrugiere.forgeline.navigation.SearchRoute as SearchKey
import fr.arthurbrugiere.forgeline.search.SearchRoute as SearchDestination
import fr.arthurbrugiere.forgeline.navigation.RepoRoute as RepoKey
import fr.arthurbrugiere.forgeline.navigation.FileRoute as FileKey
import fr.arthurbrugiere.forgeline.file.FileRoute as FileDestination
import fr.arthurbrugiere.forgeline.repo.RepoRoute as RepoDestination
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.navigation.route
import fr.arthurbrugiere.forgeline.navigation.userRoute
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.navigation.IssueRoute as IssueKey
import fr.arthurbrugiere.forgeline.navigation.NewIssueRoute as NewIssueKey
import fr.arthurbrugiere.forgeline.issue.NewIssueRoute as NewIssueDestination
import fr.arthurbrugiere.forgeline.navigation.newIssueRoute
import fr.arthurbrugiere.forgeline.navigation.ReleaseRoute as ReleaseKey
import fr.arthurbrugiere.forgeline.release.ReleaseRoute as ReleaseDestination
import fr.arthurbrugiere.forgeline.navigation.releaseRoute
import fr.arthurbrugiere.forgeline.navigation.RunRoute as RunKey
import fr.arthurbrugiere.forgeline.navigation.JobLogRoute as JobLogKey
import fr.arthurbrugiere.forgeline.actions.RunRoute as RunDestination
import fr.arthurbrugiere.forgeline.actions.JobLogRoute as JobLogDestination
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
    onSignOut: (Account) -> Unit,
    link: NavKey? = null,
    onLinkOpened: () -> Unit = {},
    navigator: AppNavigator = rememberAppNavigator(),
    /** The tab chosen to open on; null while the settings are still being read. */
    startTab: StartTab? = StartTab.AUTOMATIC,
) {
    LaunchedEffect(link) {
        if (link != null) {
            navigator.openLink(link)
            onLinkOpened()
        }
    }
    if (!navigator.settled) {
        // Which tab to open on depends on whether anyone is signed in, so wait for the session rather than flash the wrong one.
        LaunchedEffect(session, startTab) {
            if (session !is SessionState.Loading && startTab != null) navigator.settle(signedIn = session is SessionState.SignedIn, startTab = startTab)
        }
        Box(Modifier.fillMaxSize().background(Soft.colors.ground))
        return
    }
    SoftNavigation(selected = navigator.currentTab, onSelect = navigator::selectTab) {
        CompositionLocalProvider(
            LocalOpenSearch provides { navigator.navigate(SearchKey) },
            LocalOpenRelease provides { repo, tag -> navigator.navigate(repo.releaseRoute(tag)) },
            LocalOpenDiscussion provides { repo, number -> navigator.navigate(DiscussionRoute(repo.forge.host, repo.owner, repo.name, number)) },
            LocalOpenPicture provides { url, description -> navigator.navigate(PictureRoute(url, description)) },
            LocalOpenPictureLabel provides stringResource(R.string.picture_view),
        ) {
            ForgelineNavDisplay(navigator, session, onSignOut)
        }
    }
}

@Composable
private fun ForgelineNavDisplay(navigator: AppNavigator, session: SessionState, onSignOut: (Account) -> Unit) {
    val signIn = { navigator.navigate(SignInKey) }
    val openRepo = { id: RepoId -> navigator.navigate(id.route()) }
    val openIssue = { ref: IssueRef -> navigator.navigate(ref.route()) }
    // A login only means someone on a given forge: each screen passes the forge of what it shows.
    val openUser = { forge: ForgeInstance, login: String -> navigator.navigate(forge.userRoute(login)) }
    val browseTrending = { navigator.selectTab(TopLevelDestination.TRENDING) }
    val provider = entryProvider<NavKey> {
        entry<InboxRoute> {
            InboxDestination(
                session = session,
                onSignIn = signIn,
                onBrowseTrending = browseTrending,
                onOpenThread = { thread ->
                    // An unread thread opens where the reader left it, not at the title.
                    thread.route()?.let(navigator::navigate) ?: openRepo(thread.repo)
                },
            )
        }
        entry<FeedRoute> {
            FeedDestination(session, onSignIn = signIn, onOpenRepo = openRepo, onOpenIssue = openIssue, onOpenUser = openUser, onBrowseTrending = browseTrending)
        }
        entry<TrendingRoute> { TrendingDestination(session, onSignIn = signIn, onOpenRepo = openRepo) }
        entry<DiscussionRoute> { key ->
            fr.arthurbrugiere.forgeline.discussion.DiscussionRoute(
                key, session, onBack = navigator::goBack, onOpenRepo = openRepo, onOpenIssue = openIssue, onOpenUser = { openUser(key.repo.forge, it) }, onSignIn = signIn,
            )
        }
        entry<PictureRoute> { key -> PictureScreen(key, onBack = navigator::goBack, onOpenInBrowser = rememberCustomTabOpener()) }
        entry<FileKey> { key ->
            FileDestination(key, onBack = navigator::goBack, onOpenRepo = openRepo, onOpenIssue = openIssue, onOpenUser = { openUser(key.repo.forge, it) })
        }
        entry<IssueKey> { key ->
            IssueDestination(
                key, onBack = navigator::goBack, onOpenRepo = openRepo, onOpenIssue = openIssue, onOpenUser = { openUser(key.issue.repo.forge, it) },
                session = session, onSignIn = signIn,
                onNewIssue = { id -> navigator.navigate(id.newIssueRoute()) },
                onMoved = { navigator.replaceCurrent(it.route()) },
                onEditIssue = { navigator.navigate(it.repo.newIssueRoute().copy(edit = it.number, editIsPullRequest = it.isPullRequest)) },
            )
        }
        entry<NewIssueKey> { key ->
            NewIssueDestination(
                key, session = session, onBack = navigator::goBack, onSignIn = signIn,
                onCreated = { navigator.replaceCurrent(it.route()) },
            )
        }
        entry<ReleaseKey> { key ->
            ReleaseDestination(
                key, onBack = navigator::goBack, onOpenRepo = openRepo, onOpenIssue = openIssue, onOpenUser = { openUser(key.repo.forge, it) },
                onOpenRelease = { repo, tag -> navigator.navigate(repo.releaseRoute(tag)) },
            )
        }
        entry<RunKey> { key ->
            RunDestination(
                route = key,
                session = session,
                onBack = navigator::goBack,
                onOpenJob = { repo, job -> navigator.navigate(JobLogKey(repo.forge.host, repo.owner, repo.name, key.runId, job.id, job.name)) },
                onOpenUser = { openUser(key.repo.forge, it) },
            )
        }
        entry<JobLogKey> { key -> JobLogDestination(key, onBack = navigator::goBack, onSignIn = signIn) }
        entry<UserKey> { key ->
            UserDestination(key, session = session, onBack = navigator::goBack, onOpenRepo = openRepo, onSignIn = signIn)
        }
        entry<RepoKey> { key ->
            RepoDestination(
                route = key,
                session = session,
                onBack = navigator::goBack,
                onOpenRepo = openRepo,
                onOpenFile = { id, path, ref -> navigator.navigate(FileKey(id.forge.host, id.owner, id.name, path, ref)) },
                onOpenIssue = openIssue,
                onNewIssue = { id -> navigator.navigate(id.newIssueRoute()) },
                onOpenRun = { id, runId -> navigator.navigate(RunKey(id.forge.host, id.owner, id.name, runId)) },
                onOpenUser = { openUser(key.repo.forge, it) },
                onSignIn = signIn,
            )
        }
        entry<YouRoute> {
            YouScreen(
                session,
                onSignIn = signIn,
                onOpenSettings = { navigator.navigate(SettingsRoute) },
                onOpenWork = { navigator.navigate(WorkRoute) },
                onOpenProfile = { account -> openUser(account.forge, account.user.login) },
            )
        }
        entry<WorkRoute> { fr.arthurbrugiere.forgeline.work.WorkRoute(onBack = navigator::goBack, onOpenIssue = openIssue) }
        entry<SettingsRoute> {
            SettingsDestination(
                session = session,
                onSignIn = signIn,
                onSignOut = onSignOut,
                onBack = navigator::goBack,
                onOpenCredits = { navigator.navigate(CreditsKey) },
                onOpenSection = { navigator.navigate(SettingsSectionRoute(it)) },
            )
        }
        entry<SettingsSectionRoute> { key ->
            SettingsDestination(
                session = session,
                onSignIn = signIn,
                onSignOut = onSignOut,
                onBack = navigator::goBack,
                onOpenCredits = { navigator.navigate(CreditsKey) },
                section = key.section,
            )
        }
        entry<SignInKey> {
            SignInRoute(onBack = navigator::goBack, onSignedIn = navigator::goBack)
        }
        entry<SearchKey> {
            SearchDestination(onOpenRepo = openRepo, onOpenIssue = openIssue, onOpenUser = openUser, onBack = navigator::goBack, onOpenLink = navigator::navigate)
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
