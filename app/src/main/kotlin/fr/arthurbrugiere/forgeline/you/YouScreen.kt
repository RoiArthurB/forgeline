package fr.arthurbrugiere.forgeline.you

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoFilter
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoOwner
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeMark
import fr.arthurbrugiere.forgeline.core.ui.format.iconRes
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTile
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.rowHairline
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.LocalOpenSearch
import fr.arthurbrugiere.forgeline.ui.RepoSummaryRow
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

/** The You tab as the app shows it: the screen below, with every repository the accounts can reach. */
@Composable
fun YouDestination(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: (Account) -> Unit,
    onOpenOwner: (ForgeInstance, String) -> Unit,
    onOpenRepo: (RepoId) -> Unit,
) {
    if (session !is SessionState.SignedIn) {
        YouScreen(session, onSignIn, onOpenSettings, onOpenProfile = onOpenProfile)
        return
    }
    // Keyed by the accounts: signing in or out anew reads the lists again.
    val viewModel = hiltViewModel<YouViewModel>(key = session.accounts.joinToString { it.id })
    val repos by viewModel.state.collectAsStateWithLifecycle()
    YouScreen(
        session, onSignIn, onOpenSettings,
        onOpenProfile = onOpenProfile,
        repos = repos,
        onSearch = viewModel::search,
        onPick = viewModel::pick,
        onOpenOwner = { onOpenOwner(it.forge, it.login) },
        onOpenRepo = onOpenRepo,
        onRefresh = viewModel::refresh,
        onErrorShown = viewModel::errorShown,
    )
}

/**
 * Who is signed in, then every repository they can reach, most recently pushed first: a field narrows it by what
 * is typed, and a row of owners (oneself, then each organisation) by whose it is. Holding an owner opens its page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouScreen(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenProfile: (Account) -> Unit = {},
    repos: YouReposState = YouReposState(),
    onSearch: (String) -> Unit = {},
    onPick: (RepoFilter) -> Unit = {},
    onOpenOwner: (RepoOwner) -> Unit = {},
    onOpenRepo: (RepoId) -> Unit = {},
    onRefresh: () -> Unit = {},
    onErrorShown: () -> Unit = {},
    nowMillis: Long = rememberNow(repos.all),
) {
    val colors = Soft.colors
    val signedIn = session as? SessionState.SignedIn
    // A forge's logo badges owners and rows once more than one forge is signed in, as everywhere else.
    val showForge = signedIn?.accounts.orEmpty().map { it.forge }.distinct().size > 1
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.you_repos_refresh_failed)
    val hasRepos = repos.all != null
    LaunchedEffect(repos.error, hasRepos) {
        if (repos.error != null && hasRepos) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = repos.isRefreshing && hasRepos,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = repos.isRefreshing && hasRepos,
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
                    containerColor = colors.raised,
                    color = colors.accent,
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = listBottomPadding()),
                modifier = Modifier.fillMaxSize().sideSafeArea(),
            ) {
                item(key = "header") {
                    val openSearch = LocalOpenSearch.current
                    SoftHeader(
                        title = stringResource(R.string.tab_you),
                        actions = {
                            if (openSearch != null) {
                                SoftIconButton(onClick = openSearch) {
                                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search), tint = Soft.colors.ink)
                                }
                            }
                            SoftIconButton(onClick = onOpenSettings) {
                                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title), tint = Soft.colors.ink)
                            }
                        },
                    ) {
                        if (signedIn != null) {
                            // Every account, each opening its own profile on its own forge; the active one first. Slim:
                            // the tab is for the repositories below.
                            val accounts = listOf(signedIn.account) + signedIn.accounts.filter { it.id != signedIn.account.id }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                accounts.forEach { account -> AccountHeader(account, compact = true) { onOpenProfile(account) } }
                            }
                        }
                    }
                }
                notices(session, onSignIn)
                if (signedIn != null) repositories(repos, showForge, nowMillis, onSearch, onPick, onOpenOwner, onOpenRepo, onRefresh, onSignIn)
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current))
    }
}

private fun LazyListScope.notices(session: SessionState, onSignIn: () -> Unit) {
    if (session == SessionState.SignedOut) {
        item(key = "signed-out") {
            SoftNotice(
                stringResource(R.string.you_signed_out_title),
                stringResource(R.string.you_signed_out_body),
                action = stringResource(R.string.sign_in),
                onAction = onSignIn,
                icon = Icons.Outlined.PersonAdd,
            )
        }
    }
    if (session !is SessionState.SignedIn) return
    // A sign-in the forge no longer renews: everything for that account fails until it is made again.
    session.accounts.filter { it.id in session.ended }.forEach { account ->
        item(key = "ended-${account.id}") {
            SoftNotice(
                stringResource(R.string.you_ended_title, account.forge.displayName),
                stringResource(R.string.you_ended_body, account.forge.displayName, account.user.login),
                action = stringResource(R.string.you_ended_action, account.forge.displayName),
                onAction = onSignIn,
            )
        }
    }
    // A sign-in made before private repositories were asked for: say so, once per forge, with the way out.
    session.accounts.filter { it.id in session.limited }.map { it.forge }.distinct().forEach { forge ->
        item(key = "limited-${forge.host}") {
            SoftNotice(
                stringResource(R.string.you_limited_title),
                stringResource(R.string.you_limited_body, forge.displayName),
                action = stringResource(R.string.you_limited_action, forge.displayName),
                onAction = onSignIn,
            )
        }
    }
}

private fun LazyListScope.repositories(
    repos: YouReposState,
    showForge: Boolean,
    nowMillis: Long,
    onSearch: (String) -> Unit,
    onPick: (RepoFilter) -> Unit,
    onOpenOwner: (RepoOwner) -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
) {
    val all = repos.all
    when {
        all == null && repos.error != null -> item(key = "repos-error") {
            SoftNotice(
                stringResource(R.string.you_repos_error_title),
                stringResource(repos.error.message),
                action = stringResource(R.string.retry),
                onAction = onRefresh,
            )
        }
        all == null -> item(key = "repos-loading") { SoftLoadingRows(stringResource(R.string.you_repos_loading)) }
        else -> {
            item(key = "find") {
                val colors = Soft.colors
                SoftTextField(
                    value = repos.query,
                    onValueChange = onSearch,
                    placeholder = pluralStringResource(R.plurals.you_repos_find, all.repos.size, all.repos.size),
                    background = colors.surface,
                    leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.inkMuted) },
                    trailing = if (repos.query.isEmpty()) {
                        null
                    } else {
                        {
                            SoftIconButton(onClick = { onSearch("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.you_repos_clear), tint = colors.inkMuted)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                )
            }
            item(key = "owners") { Owners(all.owners, all.sharedCount, repos.filter, showForge, onPick, onOpenOwner) }
            if (repos.filter != RepoFilter.All || repos.query.isNotBlank()) {
                item(key = "count") {
                    val count = repos.shown.size
                    val filter = repos.filter
                    val total = when (filter) {
                        RepoFilter.All -> all.repos.size
                        RepoFilter.Shared -> all.sharedCount
                        is RepoFilter.Of -> filter.owner.repoCount
                    }
                    Text(
                        when (filter) {
                            RepoFilter.All -> pluralStringResource(R.plurals.you_repos_count, total, count, total)
                            RepoFilter.Shared -> pluralStringResource(R.plurals.you_repos_count_shared, total, count, total)
                            is RepoFilter.Of -> pluralStringResource(R.plurals.you_repos_count_of_owner, total, count, total, filter.owner.login)
                        },
                        style = Soft.type.meta,
                        color = Soft.colors.inkMuted,
                        modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp),
                    )
                }
            }
            if (repos.shown.isEmpty()) {
                item(key = "none") {
                    when {
                        repos.query.isNotBlank() -> SoftNotice(stringResource(R.string.you_repos_none_title), stringResource(R.string.you_repos_none_body, repos.query))
                        // A new account: nothing of one's own yet, nothing shared.
                        all.repos.isEmpty() -> SoftNotice(
                            stringResource(R.string.you_repos_first_title),
                            stringResource(R.string.you_repos_first_body),
                            icon = Icons.Outlined.Apps,
                        )
                        repos.filter == RepoFilter.Shared -> SoftNotice(stringResource(R.string.you_repos_none_title), stringResource(R.string.you_repos_none_shared))
                        else -> SoftNotice(stringResource(R.string.you_repos_none_title), stringResource(R.string.you_repos_none_owner))
                    }
                }
            }
            items(repos.shown, key = { "repo-${it.id.key}" }, contentType = { "repo" }) { repo ->
                Box(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).animateItem()) {
                    RepoSummaryRow(repo, onOpenRepo, showOwner = true, forge = repo.id.forge.takeIf { showForge }, highlight = repos.query, pushedNowMillis = nowMillis)
                }
            }
        }
    }
    item(key = "add-account") { NavigationRow(Icons.Outlined.PersonAdd, stringResource(R.string.settings_add_account), onSignIn) }
}

/**
 * Everyone whose repositories are here, as a row of logos that scrolls sideways: "All" first, then oneself, then what
 * others shared (when they did), then every organisation. A tap narrows the list to it (again, back to all); a long
 * press on an owner opens its page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Owners(owners: List<RepoOwner>, sharedCount: Int, filter: RepoFilter, showForge: Boolean, onPick: (RepoFilter) -> Unit, onOpen: (RepoOwner) -> Unit) {
    val colors = Soft.colors
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().rowHairline(start = 0.dp),
    ) {
        item(key = "all") {
            OwnerChoice(stringResource(R.string.you_repos_all), selected = filter == RepoFilter.All, onClick = { onPick(RepoFilter.All) }) {
                SoftTile(size = OwnerTile) { Icon(Icons.Outlined.Apps, contentDescription = null, tint = colors.ink) }
            }
        }
        val (yours, organisations) = owners.partition { it.isYou }
        items(yours, key = { "${it.forge.host}/${it.login}" }) { owner -> Owner(owner, filter, showForge, onPick, onOpen) }
        if (sharedCount > 0) {
            item(key = "shared") {
                OwnerChoice(stringResource(R.string.you_repos_shared), selected = filter == RepoFilter.Shared, onClick = { onPick(RepoFilter.Shared) }) {
                    SoftTile(size = OwnerTile) { Icon(Icons.Outlined.Group, contentDescription = null, tint = colors.ink) }
                }
            }
        }
        items(organisations, key = { "${it.forge.host}/${it.login}" }) { owner -> Owner(owner, filter, showForge, onPick, onOpen) }
    }
}

@Composable
private fun Owner(owner: RepoOwner, filter: RepoFilter, showForge: Boolean, onPick: (RepoFilter) -> Unit, onOpen: (RepoOwner) -> Unit) {
    val colors = Soft.colors
    val label = if (owner.isYou && !showForge) stringResource(R.string.you_repos_yours) else owner.login
    val forge = owner.forge.takeIf { showForge }
    OwnerChoice(
        label,
        selected = (filter as? RepoFilter.Of)?.owner?.let { it.login == owner.login && it.forge == owner.forge } == true,
        onClick = { onPick(RepoFilter.Of(owner)) },
        onLongClick = { onOpen(owner) },
        longClickLabel = stringResource(R.string.you_repos_open_owner, owner.login),
    ) {
        SoftTile(size = OwnerTile, badge = forge?.let { painterResource(it.iconRes) }, badgeDescription = forge?.displayName) {
            Avatar(owner.avatarUrl, owner.login, size = OwnerTile, shape = SoftTokens.TileCorner, placeholderColor = colors.surface, placeholderContentColor = colors.inkMuted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OwnerChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    tile: @Composable () -> Unit,
) {
    val colors = Soft.colors
    Column(
        Modifier
            .width(68.dp)
            .clip(SoftTokens.TileCorner)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel, role = Role.Tab)
            .semantics { this.selected = selected }
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tile()
        Text(
            label,
            style = Soft.type.meta.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) colors.ink else colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        // The pick is said by its weight and a marigold rule under it, as tabs are: no filled chip.
        Box(Modifier.padding(top = 6.dp).width(28.dp).height(3.dp).background(if (selected) colors.thumb else Color.Transparent, RoundedCornerShape(2.dp)))
    }
}

private val OwnerTile = 40.dp

/** Who is signed in, big and friendly on the field; tapping it opens their profile. */
@Composable
private fun AccountHeader(account: Account, compact: Boolean, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier
            .fillMaxWidth()
            .softPressable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            account.user.avatarUrl,
            account.user.login,
            size = if (compact) 48.dp else 64.dp,
            placeholderColor = colors.surface,
            placeholderContentColor = colors.inkMuted,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                account.user.name ?: account.user.login,
                style = Soft.type.name,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The forge goes under the handle when both don't fit on one line (large text): neither is cut.
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text("@${account.user.login}", style = Soft.type.secondary, color = colors.inkMuted)
                ForgeMark(account.forge, style = Soft.type.secondary, color = colors.inkMuted)
            }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.inkMuted)
    }
}

/** A row that leads somewhere: an icon on a tile, its name, and a chevron. */
@Composable
internal fun NavigationRow(icon: ImageVector, label: String, onClick: () -> Unit, summary: String? = null) {
    val colors = Soft.colors
    Row(
        Modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .softPressable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(SoftTokens.Tile).background(colors.surface, SoftTokens.TileCorner), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Soft.type.body, color = colors.ink)
            summary?.let { Text(it, style = Soft.type.secondary, color = colors.inkMuted) }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.inkMuted)
    }
}
