package fr.arthurbrugiere.forgeline.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.Hint
import fr.arthurbrugiere.forgeline.core.model.InboxAction
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeIcon
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoicePill
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.LocalOpenSearch
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.work.WorkUiState
import fr.arthurbrugiere.forgeline.work.WorkViewModel
import fr.arthurbrugiere.forgeline.work.workItems

@Composable
fun InboxRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenThread: (NotificationThread) -> Unit,
    onBrowseTrending: () -> Unit = {},
    onOpenIssue: (IssueRef) -> Unit = {},
) {
    if (session !is SessionState.SignedIn) {
        InboxSignedOut(session, onSignIn, onBrowseTrending)
        return
    }
    // Keyed by account so switching accounts never shows the previous inbox's state.
    val viewModel = hiltViewModel<InboxViewModel>(key = session.account.id)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notifications = rememberNotificationPrompt()
    // Coming back to the app is when the forge's own site may have been used in between.
    LifecycleResumeEffect(viewModel) {
        viewModel.shown()
        onPauseOrDispose {}
    }
    // Asked of the forges only once "Yours" is looked at: it costs each of them three searches.
    val work = if (state.filter == InboxFilter.YOURS) hiltViewModel<WorkViewModel>(key = "work-${session.account.id}") else null
    val workState = work?.state?.collectAsStateWithLifecycle()?.value
    InboxScreen(
        state = state,
        work = workState,
        onRefreshWork = { work?.refresh() },
        onWorkErrorShown = { work?.errorShown() },
        onOpenIssue = onOpenIssue,
        notificationPrompt = notifications.prompt.takeIf { state.backgroundChecks },
        onAllowNotifications = notifications.onAllow,
        onSelectFilter = viewModel::selectFilter,
        onSelectAccount = viewModel::selectAccount,
        onRefresh = viewModel::refresh,
        onOpen = { thread ->
            viewModel.opened(thread)
            onOpenThread(thread)
        },
        onMarkRead = viewModel::markRead,
        onMarkUnread = viewModel::markUnread,
        onMarkDone = viewModel::markDone,
        onMarkAllDone = viewModel::markAllDone,
        onMarkFinishedDone = viewModel::markFinishedDone,
        onUnsubscribe = viewModel::unsubscribe,
        onUndo = viewModel::undo,
        onToggleSelected = viewModel::toggleSelected,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onActOnSelected = viewModel::actOnSelected,
        onErrorShown = viewModel::errorShown,
        onActionFailureShown = viewModel::actionFailureShown,
        onDismissHint = viewModel::hintSeen,
    )
}

@Composable
private fun InboxSignedOut(session: SessionState, onSignIn: () -> Unit, onBrowseTrending: () -> Unit) {
    val colors = Soft.colors
    Column(Modifier.fillMaxSize().background(colors.ground), horizontalAlignment = Alignment.CenterHorizontally) {
        InboxHeader(filter = null, onSelectFilter = {})
        if (session == SessionState.SignedOut) {
            SoftNotice(
                stringResource(R.string.inbox_signed_out_title),
                stringResource(R.string.inbox_signed_out_body),
                action = stringResource(R.string.sign_in),
                onAction = onSignIn,
                secondaryAction = stringResource(R.string.browse_trending),
                onSecondaryAction = onBrowseTrending,
                icon = Icons.Outlined.Inbox,
            )
        }
    }
}

/** The Inbox's tinted field: the title, search and, once signed in, the filter switch (each filter its own tint). */

/** How many listed threads are about a conversation that is over, and the way to be done with them all. */
@Composable
private fun FinishedRow(count: Int, onMarkDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.inbox_finished, count, count),
            style = Soft.type.secondary,
            color = Soft.colors.inkMuted,
            modifier = Modifier.weight(1f),
        )
        SoftTonalButton(stringResource(R.string.inbox_finished_done), onClick = onMarkDone)
    }
}

/** Whether rows name their forge: only when accounts span more than one. */
internal val LocalShowForge = staticCompositionLocalOf { false }

@Composable
private fun InboxHeader(filter: InboxFilter?, onSelectFilter: (InboxFilter) -> Unit, accountPicker: (@Composable () -> Unit)? = null) {
    val colors = Soft.colors
    val openSearch = LocalOpenSearch.current
    SoftHeader(
        title = stringResource(R.string.tab_inbox),
        actions = {
            // A tile, beside search: it takes the same room at every font size, so "Inbox" never breaks.
            accountPicker?.invoke()
            if (openSearch != null) {
                SoftIconButton(onClick = openSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search), tint = Soft.colors.ink)
                }
            }
        },
        content = filter?.let {
            {
                SoftSwitch(
                    options = InboxFilter.entries.map { stringResource(it.label) },
                    selected = filter.ordinal,
                    onSelect = { onSelectFilter(InboxFilter.entries[it]) },
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(
    state: InboxUiState,
    onSelectFilter: (InboxFilter) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (NotificationThread) -> Unit,
    onMarkRead: (NotificationThread) -> Unit,
    onMarkDone: (NotificationThread) -> Unit,
    onUnsubscribe: (NotificationThread) -> Unit,
    onErrorShown: () -> Unit,
    onActionFailureShown: () -> Unit,
    modifier: Modifier = Modifier,
    notificationPrompt: NotificationPrompt? = null,
    onAllowNotifications: () -> Unit = {},
    onUndo: (PendingUndo) -> Unit = {},
    /** A repository's heading was swiped away: every thread listed under it is done. */
    onMarkAllDone: (List<NotificationThread>) -> Unit = {},
    /** Every thread listed about a conversation that is over is done. */
    onMarkFinishedDone: () -> Unit = {},
    onSelectAccount: (String?) -> Unit = {},
    onMarkUnread: (NotificationThread) -> Unit = {},
    /** A long press picks a thread; from then on a tap picks or lets go, and the bar at the bottom acts on them all. */
    onToggleSelected: (NotificationThread) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onActOnSelected: (InboxAction) -> Unit = {},
    /** What is listed under [InboxFilter.YOURS]; null until that filter is looked at. */
    work: WorkUiState? = null,
    onRefreshWork: () -> Unit = {},
    onWorkErrorShown: () -> Unit = {},
    onOpenIssue: (IssueRef) -> Unit = {},
    /** The hint shown was put away. */
    onDismissHint: (Hint) -> Unit = {},
    nowMillis: Long = rememberNow(state.groups),
) = CompositionLocalProvider(LocalShowForge provides state.showForge) {
    val colors = Soft.colors
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    val actionFailed = stringResource(R.string.inbox_action_failed)
    val hasThreads = state.groups.isNotEmpty()
    val yours = state.filter == InboxFilter.YOURS
    val workRefreshFailed = yours && work?.error != null && work.work != null
    LaunchedEffect(workRefreshFailed) {
        if (workRefreshFailed) {
            snackbar.showSnackbar(refreshFailed)
            onWorkErrorShown()
        }
    }
    LaunchedEffect(state.error, hasThreads) {
        if (state.error != null && hasThreads) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }
    // Every swipe or menu action waits a moment before reaching the forge; this is the way back. The view model
    // clears [undo] when time is up, which ends this effect and takes the snackbar away with it.
    val undoMessages = mapOf(
        InboxAction.READ to stringResource(R.string.inbox_undo_read),
        InboxAction.UNREAD to stringResource(R.string.inbox_undo_unread),
        InboxAction.DONE to stringResource(R.string.inbox_undo_done),
        InboxAction.UNSUBSCRIBE to stringResource(R.string.inbox_undo_unsubscribed),
    )
    val undoLabel = stringResource(R.string.inbox_undo)
    val resources = LocalResources.current
    LaunchedEffect(state.undo) {
        val undo = state.undo ?: return@LaunchedEffect
        // A repository swiped away, or a selection, says how many threads went with it.
        val many = when (undo.action) {
            InboxAction.DONE -> R.plurals.inbox_undo_done_many
            InboxAction.READ -> R.plurals.inbox_undo_read_many
            InboxAction.UNREAD -> R.plurals.inbox_undo_unread_many
            InboxAction.UNSUBSCRIBE -> null
        }
        val message = if (many != null && undo.others.isNotEmpty()) {
            resources.getQuantityString(many, undo.keys.size, undo.keys.size)
        } else {
            undoMessages.getValue(undo.action)
        }
        val result = snackbar.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) onUndo(undo)
    }
    LaunchedEffect(state.actionFailed) {
        if (state.actionFailed) {
            snackbar.showSnackbar(actionFailed)
            onActionFailureShown()
        }
    }

    val selecting = state.selected.isNotEmpty()
    // Back lets the selection go before it leaves the Inbox.
    BackHandler(selecting) { onClearSelection() }
    val picking = Picking(state.selected, onToggleSelected)
    val listState = rememberLazyListState()
    // Everything else reads owner by owner, then repository by repository: grouped once per list, not each time the
    // screen recomposes (a refresh starting or ending, an undo offered).
    val owners = remember(state.groups) {
        state.groups.filter { it.section != InboxSection.NEEDS_YOU }.associate { it.section to it.threads.byOwnerAndRepo() }
    }
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        // Under "Yours" pulling asks the forges for the work again, not for the notifications.
        val refreshing = if (yours) work?.isLoading == true && work.work != null else state.isRefreshing
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = if (yours) onRefreshWork else onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = refreshing,
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
                // Room for the selection bar, so the last threads can still be reached under it.
                contentPadding = PaddingValues(bottom = listBottomPadding() + if (selecting) 64.dp else 0.dp),
                modifier = Modifier.fillMaxSize().sideSafeArea(),
            ) {
                item(key = "header", contentType = "header") {
                    InboxHeader(
                        state.filter,
                        onSelectFilter,
                        accountPicker = if (state.accountTabs.isNotEmpty()) {
                            {
                                // "All" first, then one per account, each naming its forge (two can share a login).
                                val allAccounts = stringResource(R.string.inbox_all_accounts)
                                SoftChoicePill(
                                    name = stringResource(R.string.choice_account),
                                    options = listOf(allAccounts) +
                                        state.accountTabs.map { "@${it.user.login} · ${it.forge.displayName}" },
                                    selected = state.accountTabs.indexOfFirst { it.id == state.selectedAccountId } + 1,
                                    onSelect = { onSelectAccount(state.accountTabs.getOrNull(it - 1)?.id) },
                                    // Beside the title, a tile with the chosen account's forge (or every account's glyph); the menu
                                    // and screen readers say the full "@login · Forge".
                                    tileOnly = true,
                                    leading = { index, color ->
                                        val account = state.accountTabs.getOrNull(index - 1)
                                        if (account != null) {
                                            ForgeIcon(account.forge, size = 18.dp, tint = color, contentDescription = null)
                                        } else {
                                            Icon(Icons.Outlined.Groups, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                                        }
                                    },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
                if (notificationPrompt != null) {
                    item(key = "prompt") {
                        NotificationPromptCard(
                            notificationPrompt,
                            onAllowNotifications,
                            Modifier.widthIn(max = SoftTokens.MaxReadingWidth).padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        )
                    }
                }
                state.hint?.let { hint ->
                    item(key = "hint-$hint") {
                        InboxHintCard(
                            hint,
                            onDismiss = { onDismissHint(hint) },
                            Modifier.widthIn(max = SoftTokens.MaxReadingWidth).padding(start = 16.dp, end = 16.dp, top = 8.dp).animateItem(),
                        )
                    }
                }
                // The threads about what is over, done at once: not while some are being picked, one thing at a time.
                val finished = state.finished.size
                if (!yours && finished > 0 && state.selected.isEmpty()) {
                    item(key = "finished") {
                        FinishedRow(finished, onMarkFinishedDone, Modifier.widthIn(max = SoftTokens.MaxReadingWidth).animateItem())
                    }
                }
                when {
                    yours -> workItems(work ?: WorkUiState(), nowMillis, onRefreshWork, onOpenIssue)
                    !hasThreads && state.error != null -> item(key = "error") {
                        SoftNotice(
                            stringResource(R.string.inbox_error_title),
                            stringResource(
                                if (state.error is ForgeError.Http && state.error.status == 403) R.string.inbox_error_scope else state.error.message,
                            ),
                            action = stringResource(R.string.retry),
                            onAction = onRefresh,
                        )
                    }
                    !hasThreads && state.syncedAtMillis == null -> item(key = "loading") {
                        SoftLoadingRows(stringResource(R.string.inbox_loading))
                    }
                    !hasThreads -> item(key = "empty") {
                        SoftNotice(
                            stringResource(if (state.filter == InboxFilter.UNREAD) R.string.inbox_caught_up_title else R.string.inbox_empty_title),
                            stringResource(if (state.filter == InboxFilter.UNREAD) R.string.inbox_caught_up_body else R.string.inbox_empty_body),
                            // Caught up is the Inbox's goal reached; a filter showing nothing is no event.
                            icon = if (state.filter == InboxFilter.UNREAD) Icons.Outlined.DoneAll else null,
                        )
                    }
                    else -> state.groups.forEach { group ->
                        stickyHeader(key = "section-${group.section}", contentType = "section") {
                            SectionHeading(group.section, group.threads.count { it.unread }, Modifier.animateItem())
                        }
                        if (group.section == InboxSection.NEEDS_YOU) {
                            threadItems(group.threads, group.section, nowMillis, picking, onOpen, onMarkRead, onMarkUnread, onMarkDone, onUnsubscribe)
                        } else {
                            // An owner with a single repository gets one combined heading; one with several heads them all.
                            owners.getValue(group.section).forEach { (forgeOwner, repos) ->
                                if (repos.size > 1) {
                                    item(key = "owner-${forgeOwner.first.host}-${forgeOwner.second}", contentType = "owner") {
                                        OwnerHeading(repos.values.first().first(), Modifier.widthIn(max = SoftTokens.MaxReadingWidth).animateItem())
                                    }
                                }
                                repos.forEach { (repo, threads) ->
                                    item(key = "repo-${repo.key}", contentType = "repo") {
                                        RepoHeading(
                                            threads.first(), showOwner = repos.size == 1, onMarkAllDone = { onMarkAllDone(threads) },
                                            Modifier.widthIn(max = SoftTokens.MaxReadingWidth).animateItem(),
                                        )
                                    }
                                    threadItems(threads, group.section, nowMillis, picking, onOpen, onMarkRead, onMarkUnread, onMarkDone, onUnsubscribe)
                                }
                            }
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        if (selecting) {
            SelectionBar(
                threads = state.selectedThreads,
                onSelectAll = onSelectAll,
                onClear = onClearSelection,
                onAct = onActOnSelected,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = LocalBottomBarSpace.current + 8.dp),
            )
        }
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current))
    }
}
