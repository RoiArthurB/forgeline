package fr.arthurbrugiere.forgeline.user

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LinkKind
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.format.iconRes
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChipTabs
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.navigation.UserRoute
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import fr.arthurbrugiere.forgeline.repo.ReadmeBody
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.LocalUserSettings
import fr.arthurbrugiere.forgeline.ui.RepoSummaryRow
import fr.arthurbrugiere.forgeline.ui.ShareLinkButton
import fr.arthurbrugiere.forgeline.ui.SignInToRead
import fr.arthurbrugiere.forgeline.ui.asksToSignIn
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.loadable
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.overBottomBarOrKeyboard
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.rememberLinkOpener
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun UserRoute(
    route: UserRoute,
    session: SessionState,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onSignIn: () -> Unit,
    onOpenIssue: (IssueRef) -> Unit = {},
    onOpenUser: (String) -> Unit = {},
) {
    val viewModel = hiltViewModel<UserViewModel, UserViewModel.Factory>(key = "${route.host}/${route.login.lowercase()}") {
        it.create(route.forge, route.login)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberLinkOpener()
    val openDiscussion = fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion.current
    val openWiki = fr.arthurbrugiere.forgeline.ui.LocalOpenWiki.current
    val openCommit = fr.arthurbrugiere.forgeline.ui.LocalOpenCommit.current
    val openFile = fr.arthurbrugiere.forgeline.ui.LocalOpenFile.current
    val openRelease = fr.arthurbrugiere.forgeline.ui.LocalOpenRelease.current
    val openTopic = fr.arthurbrugiere.forgeline.ui.LocalOpenTopic.current
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
        onLinkClick = { url ->
            openForgeLink(
                url, route.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRelease = openRelease, onOpenDiscussion = openDiscussion,
                onOpenWiki = openWiki, onOpenCommit = openCommit, onOpenTopic = openTopic, onOpenFile = openFile,
            )
        },
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
    /** A link in the profile's README: one to a repository, a conversation or a person opens in the app. */
    onLinkClick: (String) -> Unit = onOpenUrl,
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
                    onBack = onBack,
                    backDescription = stringResource(R.string.navigate_up),
                    actions = {
                        ShareLinkButton("${state.forge.webUrl}/${state.login}", state.login)
                        SoftIconButton(onClick = { onOpenUrl("${state.forge.webUrl}/${state.login}") }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, state.forge.displayName), tint = Soft.colors.ink)
                        }
                    },
                ) {
                    if (profile != null) {
                        Header(profile, state.following, signedIn, onToggleFollow, onSignIn, onOpenUrl, social = state.forge.type.isSocial)
                    } else {
                        Text(fr.arthurbrugiere.forgeline.ui.handleOf(state.login), style = Soft.type.hero, color = Soft.colors.ink, modifier = Modifier.semantics { heading() })
                    }
                }
            }
            state.contributions?.takeIf { profile != null }?.let { contributions ->
                item(key = "contributions") { ContributionGraph(contributions, today = remember { java.time.LocalDate.now() }) }
            }
            when {
                profile == null && state.error.asksToSignIn(signedIn) -> item(key = "sign-in") { SignInToRead(state.forge, onSignIn) }
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
                    // Nobody stars anything on SourceHut: no list of stars to show. Regression: the tab said the forge couldn't.
                    val tabs = UserTab.entries.filter { (it != UserTab.README || state.readme != null) && (it != UserTab.STARRED || state.forge.type.isSocial) }
                    stickyHeader(key = "tabs") {
                        SoftChipTabs(
                            options = tabs.map {
                                stringResource(
                                    when (it) {
                                        UserTab.README -> R.string.user_tab_readme
                                        UserTab.REPOS -> R.string.user_tab_repos
                                        UserTab.STARRED -> R.string.user_tab_starred
                                    },
                                )
                            },
                            selected = tabs.indexOf(state.tab).coerceAtLeast(0),
                            onSelect = { onSelectTab(tabs[it]) },
                        )
                    }
                    val readme = state.readme
                    if (state.tab == UserTab.README && readme != null) {
                        item(key = "readme") {
                            val directory = readme.readme.path.substringBeforeLast('/', missingDelimiterValue = "").let { if (it.isEmpty()) "" else "$it/" }
                            val context = remember(readme) {
                                ReadmeContext(rawBaseUrl = readme.repo.rawBaseUrl("HEAD"), blobBaseUrl = readme.repo.blobBaseUrl("HEAD"), directory = directory)
                            }
                            ReadmeBody(readme.readme.markdown, context, onLinkClick)
                        }
                        return@LazyColumn
                    }
                    val (list, empty) = when (state.tab) {
                        UserTab.STARRED -> state.starred to R.string.user_no_starred
                        else -> state.repos to R.string.user_no_repos
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
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).overBottomBarOrKeyboard())
    }
}

@Composable
private fun Header(
    profile: UserProfile,
    following: Boolean?,
    signedIn: Boolean,
    onToggleFollow: () -> Unit,
    /** Null on a forge the app can't sign in to: following isn't offered there. */
    onSignIn: (() -> Unit)?,
    onOpenUrl: (String) -> Unit,
    /** Whether people follow each other on the forge: SourceHut shows neither counts nor a follow button. */
    social: Boolean = true,
) {
    val colors = Soft.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(profile.avatarUrl, profile.login, size = 72.dp, placeholderColor = colors.surface, placeholderContentColor = colors.inkMuted)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The name leads when there is one; otherwise the login is the page's heading.
                profile.name?.let { Text(it, style = Soft.type.hero, color = colors.ink, modifier = Modifier.semantics { heading() }) }
                Text(fr.arthurbrugiere.forgeline.ui.handleOf(profile.login), style = Soft.type.secondary, color = colors.inkMuted, modifier = if (profile.name == null) Modifier.semantics { heading() } else Modifier)
                if (profile.isOrganization) SoftTag(stringResource(R.string.user_organization), background = colors.surface)
            }
        }
        profile.bio?.let { Text(it, style = Soft.type.lead, color = colors.ink, modifier = Modifier.widthIn(max = SoftTokens.MaxMeasure)) }
        profile.company?.let { Detail(Icons.Outlined.Business, it) }
        profile.location?.let { Detail(Icons.Outlined.LocationOn, it) }
        // Roomy, the links stand one under another, each as tall as a finger with no gap between: 20dp lines 10dp apart
        // leave a tap between two of them to whichever is nearer. Compact, they keep those lines.
        val roomy = LocalUserSettings.current.roomyTargets
        if (profile.website != null || profile.links.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(if (roomy) 0.dp else 10.dp)) {
                profile.website?.let { site ->
                    val url = if (site.startsWith("http")) site else "https://$site"
                    Detail(Icons.Outlined.Link, site.removePrefix("https://").removePrefix("http://"), Modifier.linkRow(roomy) { onOpenUrl(url) }, link = true)
                }
                // Each other place by its logo and the way people write it ("@ana@mastodon.social"); the service's name is read
                // out with it, since the logo is the only place it's written.
                profile.links.forEach { link ->
                    val icon = link.kind.iconRes?.let { painterResource(it) } ?: rememberVectorPainter(if (link.kind == LinkKind.EMAIL) Icons.Outlined.MailOutline else Icons.Outlined.Link)
                    Detail(
                        icon,
                        link.label,
                        Modifier
                            .linkRow(roomy, link.kind.displayName) { onOpenUrl(link.url) }
                            .semantics(mergeDescendants = true) { contentDescription = "${link.kind.displayName}, ${link.label}" },
                        link = true,
                    )
                }
            }
        }
        if (!profile.isOrganization && social) {
            Text(
                stringResource(R.string.user_followers, compactCount(profile.followers), compactCount(profile.following)),
                style = Soft.type.meta,
                color = colors.inkMuted,
            )
        }
        val action = Modifier.padding(top = 4.dp)
        when {
            !social -> Unit
            !signedIn -> if (onSignIn != null) SoftButton(stringResource(R.string.user_follow), onSignIn, action)
            following == true -> Box(
                action.clip(SoftTokens.Pill).background(colors.surface).clickable(role = Role.Button, onClick = onToggleFollow).heightIn(min = 48.dp).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.user_following), style = Soft.type.control, color = colors.ink) }
            following == false -> SoftButton(stringResource(R.string.user_follow), onToggleFollow, action)
            // Unknown or own profile: no follow action.
            else -> Unit
        }
    }
}

/** A profile link's row, pressed as a pill around its words, which keep the whole width; as tall as a finger when [roomy]. */
private fun Modifier.linkRow(roomy: Boolean, label: String? = null, onClick: () -> Unit): Modifier = clip(SoftTokens.Pill)
    .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
    .heightIn(min = if (roomy) 48.dp else 0.dp)

@Composable
private fun Detail(icon: ImageVector, text: String, modifier: Modifier = Modifier, link: Boolean = false) = Detail(rememberVectorPainter(icon), text, modifier, link)

@Composable
private fun Detail(icon: Painter, text: String, modifier: Modifier = Modifier, link: Boolean = false) {
    val colors = Soft.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (link) colors.accent else colors.inkMuted)
        Text(text, style = Soft.type.secondary, color = if (link) colors.accent else colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
