package fr.arthurbrugiere.forgeline.user

import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import fr.arthurbrugiere.forgeline.session.signedInOn
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.navigation.UserRoute
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.RepoSummaryRow
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.loadable
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChipTabs
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue

@Composable
fun UserRoute(
    route: UserRoute,
    session: SessionState,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onSignIn: () -> Unit,
) {
    val viewModel = hiltViewModel<UserViewModel, UserViewModel.Factory>(key = "${route.host}/${route.login.lowercase()}") {
        it.create(route.forge, route.login)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    UserScreen(
        state = state,
        signedIn = session.signedInOn(route.forge),
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
    val colors = Soft.colors
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = listBottomPadding()),
            modifier = Modifier.fillMaxSize().sideSafeArea(),
        ) {
            item(key = "header") {
                SoftHeader(
                    tint = colors.fields[1],
                    onBack = onBack,
                    backDescription = stringResource(R.string.navigate_up),
                    actions = {
                        IconButton(onClick = { onOpenUrl("${state.forge.webUrl}/${state.login}") }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, state.forge.displayName), tint = colors.ink)
                        }
                    },
                ) {
                    if (profile != null) {
                        Header(profile, state.following, signedIn, onToggleFollow, onSignIn, onOpenUrl)
                    } else {
                        Text("@${state.login}", style = Soft.type.hero, color = colors.ink, modifier = Modifier.semantics { heading() })
                    }
                }
            }
            when {
                profile == null && state.error != null -> item(key = "error") {
                    SoftNotice(
                        stringResource(R.string.user_error_title),
                        stringResource(state.error.message),
                        action = stringResource(R.string.retry),
                        onAction = onRetry,
                    )
                }
                profile == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.user_loading), rows = 3, leadingDot = false) }
                else -> {
                    stickyHeader(key = "tabs") {
                        SoftChipTabs(
                            options = UserTab.entries.map { stringResource(if (it == UserTab.REPOS) R.string.user_tab_repos else R.string.user_tab_starred) },
                            selected = state.tab.ordinal,
                            onSelect = { onSelectTab(UserTab.entries[it]) },
                        )
                    }
                    val (list, empty) = when (state.tab) {
                        UserTab.REPOS -> state.repos to R.string.user_no_repos
                        UserTab.STARRED -> state.starred to R.string.user_no_starred
                    }
                    loadable(list, empty, onRetry) { repos ->
                        items(repos, key = { "repo-${it.id.fullName}" }) {
                            // Someone's own repositories all share their avatar; starred ones show each owner's.
                            RepoSummaryRow(it, onOpenRepo, showOwner = state.tab == UserTab.STARRED)
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current)) { data ->
            Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = colors.ink, contentColor = colors.ground)
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
    val colors = Soft.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(profile.avatarUrl, profile.login, size = 72.dp, placeholderColor = colors.ground, placeholderContentColor = colors.inkMuted)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The name leads when there is one; otherwise the login is the page's heading.
                profile.name?.let { Text(it, style = Soft.type.hero, color = colors.ink, modifier = Modifier.semantics { heading() }) }
                Text("@${profile.login}", style = Soft.type.secondary, color = colors.inkMuted, modifier = if (profile.name == null) Modifier.semantics { heading() } else Modifier)
                if (profile.isOrganization) SoftTag(stringResource(R.string.user_organization), background = colors.ground)
            }
        }
        profile.bio?.let { Text(it, style = Soft.type.body.copy(fontSize = 16.sp), color = colors.ink, modifier = Modifier.widthIn(max = SoftTokens.MaxMeasure)) }
        profile.company?.let { Detail(Icons.Outlined.Business, it) }
        profile.location?.let { Detail(Icons.Outlined.LocationOn, it) }
        profile.website?.let { site ->
            val url = if (site.startsWith("http")) site else "https://$site"
            Detail(Icons.Outlined.Link, site.removePrefix("https://").removePrefix("http://"), Modifier.clip(SoftTokens.Pill).clickable { onOpenUrl(url) }, link = true)
        }
        if (!profile.isOrganization) {
            Text(
                stringResource(R.string.user_followers, compactCount(profile.followers), compactCount(profile.following)),
                style = Soft.type.meta,
                color = colors.inkMuted,
            )
        }
        val action = Modifier.padding(top = 4.dp)
        when {
            !signedIn -> SoftButton(stringResource(R.string.user_follow), onSignIn, action)
            following == true -> Box(
                action.clip(SoftTokens.Pill).background(colors.ground).clickable(role = Role.Button, onClick = onToggleFollow).heightIn(min = 48.dp).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.user_following), style = Soft.type.control, color = colors.ink) }
            following == false -> SoftButton(stringResource(R.string.user_follow), onToggleFollow, action)
            // Unknown or own profile: no follow action.
            else -> Unit
        }
    }
}

@Composable
private fun Detail(icon: ImageVector, text: String, modifier: Modifier = Modifier, link: Boolean = false) {
    val colors = Soft.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (link) colors.accent else colors.inkMuted)
        Text(text, style = Soft.type.secondary, color = if (link) colors.accent else colors.ink)
    }
}

