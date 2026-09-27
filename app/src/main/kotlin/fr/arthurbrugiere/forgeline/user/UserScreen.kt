package fr.arthurbrugiere.forgeline.user

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.navigation.UserRoute
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.Badge
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.loadable
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener

@Composable
fun UserRoute(
    route: UserRoute,
    session: SessionState,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onSignIn: () -> Unit,
) {
    val viewModel = hiltViewModel<UserViewModel, UserViewModel.Factory>(key = route.login.lowercase()) { it.create(route.login) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    UserScreen(
        state = state,
        signedIn = session is SessionState.SignedIn,
        onBack = onBack,
        onSelectTab = viewModel::selectTab,
        onRetry = viewModel::retry,
        onToggleFollow = viewModel::toggleFollow,
        onSignIn = onSignIn,
        onOpenRepo = onOpenRepo,
        onOpenUrl = openUrl,
        onFollowFailureShown = viewModel::followFailureShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UserScreen(
    state: UserUiState,
    signedIn: Boolean,
    onBack: () -> Unit,
    onSelectTab: (UserTab) -> Unit,
    onRetry: () -> Unit,
    onToggleFollow: () -> Unit,
    onSignIn: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenUrl: (String) -> Unit,
    onFollowFailureShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    val snackbar = remember { SnackbarHostState() }
    val followFailed = stringResource(R.string.user_follow_failed)
    LaunchedEffect(state.followFailed) {
        if (state.followFailed) {
            snackbar.showSnackbar(followFailed)
            onFollowFailureShown()
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.login) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenUrl("https://github.com/${state.login}") }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                profile == null && state.error != null -> EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.user_error_title),
                    body = stringResource(state.error.message),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry,
                )
                profile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "header") { Header(profile, state.following, signedIn, onToggleFollow, onSignIn, onOpenUrl) }
                    stickyHeader(key = "tabs") {
                        PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
                            UserTab.entries.forEach { tab ->
                                Tab(
                                    selected = tab == state.tab,
                                    onClick = { onSelectTab(tab) },
                                    text = { Text(stringResource(if (tab == UserTab.REPOS) R.string.user_tab_repos else R.string.user_tab_starred)) },
                                    selectedContentColor = MaterialTheme.colorScheme.primary,
                                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    val (list, empty) = when (state.tab) {
                        UserTab.REPOS -> state.repos to R.string.user_no_repos
                        UserTab.STARRED -> state.starred to R.string.user_no_starred
                    }
                    loadable(list, empty, onRetry) { repos ->
                        items(repos, key = { "repo-${it.id.fullName}" }) { RepoRow(it, onOpenRepo) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    profile: UserProfile,
    following: Boolean?,
    signedIn: Boolean,
    onToggleFollow: () -> Unit,
    onSignIn: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(profile.avatarUrl, profile.login, size = 72.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                profile.name?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                Text("@${profile.login}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (profile.isOrganization) Badge(stringResource(R.string.user_organization))
            }
        }
        profile.bio?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        profile.company?.let { Detail(Icons.Outlined.Business, it) }
        profile.location?.let { Detail(Icons.Outlined.LocationOn, it) }
        profile.website?.let { site ->
            val url = if (site.startsWith("http")) site else "https://$site"
            Detail(Icons.Outlined.Link, site.removePrefix("https://").removePrefix("http://"), Modifier.clickable { onOpenUrl(url) })
        }
        if (!profile.isOrganization) {
            Text(
                stringResource(R.string.user_followers, compactCount(profile.followers), compactCount(profile.following)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            !signedIn -> Button(onClick = onSignIn) { Text(stringResource(R.string.user_follow)) }
            following == true -> FilledTonalButton(onClick = onToggleFollow) { Text(stringResource(R.string.user_following)) }
            following == false -> Button(onClick = onToggleFollow) { Text(stringResource(R.string.user_follow)) }
            // Unknown or own profile: no follow action.
            else -> Unit
        }
    }
}

@Composable
private fun Detail(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RepoRow(repo: RepoSummary, onOpenRepo: (RepoId) -> Unit) {
    ListItem(
        headlineContent = { Text(repo.id.fullName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repo.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    repo.language?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Outlined.StarBorder, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(compactCount(repo.stars), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        modifier = Modifier.clickable { onOpenRepo(repo.id) },
    )
    HorizontalDivider()
}
