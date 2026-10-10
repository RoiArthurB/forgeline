package fr.arthurbrugiere.forgeline.inbox

import fr.arthurbrugiere.forgeline.core.model.SwipeAction
import fr.arthurbrugiere.forgeline.ui.LocalUserSettings
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.ui.rememberNow
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoicePill
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import fr.arthurbrugiere.forgeline.core.ui.format.forgeInlineContent
import fr.arthurbrugiere.forgeline.core.ui.format.appendForge
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeIcon
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChipTabs
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.ui.platform.LocalResources
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Close
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Snackbar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.LocalOpenSearch
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.graphics.Color
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftColors
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLight
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftPill
import fr.arthurbrugiere.forgeline.core.ui.soft.animationsEnabled
import fr.arthurbrugiere.forgeline.feed.unbreakable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import fr.arthurbrugiere.forgeline.ui.Avatar

@Composable
fun InboxRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenThread: (NotificationThread) -> Unit,
    onBrowseTrending: () -> Unit = {},
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
    InboxScreen(
        state = state,
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
        onMarkDone = viewModel::markDone,
        onMarkAllDone = viewModel::markAllDone,
        onUnsubscribe = viewModel::unsubscribe,
        onUndo = viewModel::undo,
        onErrorShown = viewModel::errorShown,
        onActionFailureShown = viewModel::actionFailureShown,
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
            )
        }
    }
}

/** The Inbox's tinted field: the title, search and, once signed in, the filter switch (each filter its own tint). */
/** Whether rows name their forge: only when accounts span more than one. */
private val LocalShowForge = staticCompositionLocalOf { false }

@Composable
private fun InboxHeader(filter: InboxFilter?, onSelectFilter: (InboxFilter) -> Unit, accountPicker: (@Composable () -> Unit)? = null) {
    val colors = Soft.colors
    val openSearch = LocalOpenSearch.current
    // Beside the short title while it fits; at very large fonts it drops under the title so "Inbox" never breaks.
    val pickerInRow = LocalDensity.current.fontScale <= 1.3f
    SoftHeader(
        tint = colors.fields[filter?.ordinal ?: 0],
        title = stringResource(R.string.tab_inbox),
        actions = {
            if (pickerInRow) accountPicker?.invoke()
            if (openSearch != null) {
                IconButton(onClick = openSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search), tint = colors.ink)
                }
            }
        },
        content = filter?.let {
            {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!pickerInRow) accountPicker?.invoke()
                    SoftSwitch(
                        options = InboxFilter.entries.map { stringResource(it.label) },
                        selected = filter.ordinal,
                        onSelect = { onSelectFilter(InboxFilter.entries[it]) },
                    )
                }
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
    onSelectAccount: (String?) -> Unit = {},
    nowMillis: Long = rememberNow(state.groups),
) = CompositionLocalProvider(LocalShowForge provides state.showForge) {
    val colors = Soft.colors
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    val actionFailed = stringResource(R.string.inbox_action_failed)
    val hasThreads = state.groups.isNotEmpty()
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
        InboxAction.DONE to stringResource(R.string.inbox_undo_done),
        InboxAction.UNSUBSCRIBE to stringResource(R.string.inbox_undo_unsubscribed),
    )
    val undoLabel = stringResource(R.string.inbox_undo)
    val resources = LocalResources.current
    LaunchedEffect(state.undo) {
        val undo = state.undo ?: return@LaunchedEffect
        // A repository swiped away says how many threads went with it.
        val message = if (undo.action == InboxAction.DONE && undo.others.isNotEmpty()) {
            resources.getQuantityString(R.plurals.inbox_undo_done_many, undo.keys.size, undo.keys.size)
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

    val listState = rememberLazyListState()
    // Everything else reads owner by owner, then repository by repository: grouped once per list, not each time the
    // screen recomposes (a refresh starting or ending, an undo offered).
    val owners = remember(state.groups) {
        state.groups.filter { it.section != InboxSection.NEEDS_YOU }.associate { it.section to it.threads.byOwnerAndRepo() }
    }
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing,
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
                                    // Beside the title the pill says "All accounts" or "@login" with the account's logo; the menu
                                    // and screen readers keep the full "@login · Forge".
                                    shortLabel = { index -> state.accountTabs.getOrNull(index - 1)?.let { "@${it.user.login}" } ?: allAccounts },
                                    maxWidth = 160.dp,
                                    leading = { index, color ->
                                        state.accountTabs.getOrNull(index - 1)?.let { ForgeIcon(it.forge, size = 16.dp, tint = color, contentDescription = null) }
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
                when {
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
                        )
                    }
                    else -> state.groups.forEach { group ->
                        stickyHeader(key = "section-${group.section}", contentType = "section") {
                            SectionHeading(group.section, group.threads.count { it.unread }, Modifier.animateItem())
                        }
                        if (group.section == InboxSection.NEEDS_YOU) {
                            threadItems(group.threads, group.section, nowMillis, onOpen, onMarkRead, onMarkDone, onUnsubscribe)
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
                                    threadItems(threads, group.section, nowMillis, onOpen, onMarkRead, onMarkDone, onUnsubscribe)
                                }
                            }
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current)) { data ->
            Snackbar(
                data,
                shape = RoundedCornerShape(16.dp),
                containerColor = colors.ink,
                contentColor = colors.ground,
                actionColor = snackbarAction(colors),
            )
        }
    }
}

/** Threads by owner, then by repository, in list order. By forge too: "acme" on GitHub and on Codeberg are two owners. */
private fun List<NotificationThread>.byOwnerAndRepo(): List<Pair<Pair<ForgeInstance, String>, Map<RepoId, List<NotificationThread>>>> =
    groupBy { it.repo.forge to it.repo.owner.lowercase() }.map { (forgeOwner, owned) -> forgeOwner to owned.groupBy { it.repo } }

private fun LazyListScope.threadItems(
    threads: List<NotificationThread>,
    section: InboxSection,
    nowMillis: Long,
    onOpen: (NotificationThread) -> Unit,
    onMarkRead: (NotificationThread) -> Unit,
    onMarkDone: (NotificationThread) -> Unit,
    onUnsubscribe: (NotificationThread) -> Unit,
) {
    // Keyed by account and id: two forges can use the same thread id, and duplicate keys crash the list.
    items(threads, key = { "thread-${it.key}" }, contentType = { "thread" }) { thread ->
        ThreadRow(
            thread, section, nowMillis, onOpen, onMarkRead, onMarkDone, onUnsubscribe,
            Modifier.widthIn(max = SoftTokens.MaxReadingWidth).animateItem(),
        )
    }
}

/** An owner (user or organisation) with several repositories under Everything else: its avatar and login. */
@Composable
private fun OwnerHeading(thread: NotificationThread, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OwnerAvatar(thread, 24.dp)
        Spacer(Modifier.width(10.dp))
        Text(thread.repo.owner, style = Soft.type.control, color = Soft.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        HeadingForge(thread)
    }
}

/**
 * A repository under Everything else. Alone under its owner: the owner's avatar, `owner/` muted and the name. Under an
 * owner heading: just the name, lined up with the threads below it. Swiped away like a thread, it takes every thread
 * listed under it: all done at once.
 */
@Composable
private fun RepoHeading(thread: NotificationThread, showOwner: Boolean, onMarkAllDone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    val markAllDone = stringResource(R.string.inbox_mark_all_done)
    val swipes = LocalUserSettings.current
    // Not saved, like a thread's: a heading brought back by Undo starts settled.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.padding(start = 8.dp, end = 8.dp, top = if (showOwner) 10.dp else 6.dp),
        // A repository is swiped away toward the side that means "done" for a thread, if one does.
        enableDismissFromStartToEnd = swipes.inboxSwipeRight == SwipeAction.DONE,
        enableDismissFromEndToStart = swipes.inboxSwipeLeft == SwipeAction.DONE,
        backgroundContent = { SwipeBackground(swipe.dismissDirection, SwipeAction.DONE) },
        onDismiss = { direction -> if (direction != SwipeToDismissBoxValue.Settled) onMarkAllDone() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(SoftTokens.RowCorner)
                .background(colors.ground)
                // Under an owner heading, the name lines up with its threads' pills and titles.
                .padding(start = if (showOwner) 12.dp else 28.dp, end = 12.dp, top = 4.dp, bottom = 2.dp)
                .semantics(mergeDescendants = true) {
                    heading()
                    // Swipes are invisible to screen readers: the same action as an accessibility action.
                    customActions = listOf(CustomAccessibilityAction(markAllDone) { onMarkAllDone(); true })
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showOwner) {
                OwnerAvatar(thread, 24.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                buildAnnotatedString {
                    if (showOwner) withStyle(SpanStyle(color = colors.inkMuted)) { append("${thread.repo.owner}/\u2060") }
                    append(thread.repo.name)
                },
                style = Soft.type.control,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            HeadingForge(thread)
        }
    }
}

/** The forge's logo after a heading's name, once accounts span several forges; the heading reads out the forge too. */
@Composable
private fun HeadingForge(thread: NotificationThread) {
    if (!LocalShowForge.current) return
    Spacer(Modifier.width(8.dp))
    ForgeIcon(thread.repo.forge, size = 16.dp, tint = Soft.colors.inkMuted)
}

/** The repository owner's avatar (a user or an organisation), its initial on the soft surface until it loads. */
@Composable
private fun OwnerAvatar(thread: NotificationThread, size: Dp) {
    val colors = Soft.colors
    Avatar(
        thread.ownerAvatarUrl,
        thread.repo.owner,
        size = size,
        placeholderColor = colors.surface,
        placeholderContentColor = colors.inkMuted,
        modifier = Modifier.clearAndSetSemantics {},
    )
}

/** A section's title over its threads, pinned while they scroll; the unread count ticks as it changes. */
@Composable
private fun SectionHeading(section: InboxSection, unread: Int, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    val animations = animationsEnabled()
    Row(
        modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .background(colors.ground)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (section == InboxSection.NEEDS_YOU) R.string.inbox_section_needs_you else R.string.inbox_section_others),
            style = Soft.type.section,
            color = colors.ink,
        )
        AnimatedVisibility(unread > 0, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            Row {
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState = unread,
                    transitionSpec = {
                        if (!animations) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            val down = targetState < initialState
                            (slideInVertically { if (down) -it else it } + fadeIn()) togetherWith
                                (slideOutVertically { if (down) it else -it } + fadeOut())
                        }
                    },
                    label = "unread",
                ) { count ->
                    SoftTag("$count", background = if (section == InboxSection.NEEDS_YOU) colors.fields[0] else colors.surface)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThreadRow(
    thread: NotificationThread,
    section: InboxSection,
    nowMillis: Long,
    onOpen: (NotificationThread) -> Unit,
    onMarkRead: (NotificationThread) -> Unit,
    onMarkDone: (NotificationThread) -> Unit,
    onUnsubscribe: (NotificationThread) -> Unit,
    modifier: Modifier = Modifier,
) {
    val unreadState = stringResource(R.string.inbox_unread_state)
    val colors = Soft.colors
    // Not saved: a thread brought back by Undo keeps its list key, so a saved swipe state would come back dismissed
    // and mark it done again at once. A returning row always starts settled.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    val scope = rememberCoroutineScope()
    val markReadLabel = stringResource(R.string.inbox_mark_read)
    val doneLabel = stringResource(R.string.inbox_mark_done)
    val unsubscribeLabel = stringResource(R.string.inbox_unsubscribe)
    val animations = animationsEnabled()
    val toEnd = LocalUserSettings.current.inboxSwipeRight
    val toStart = LocalUserSettings.current.inboxSwipeLeft
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        // Each side does what Settings says; a side with nothing to do here doesn't move.
        enableDismissFromStartToEnd = toEnd.appliesTo(thread),
        enableDismissFromEndToStart = toStart.appliesTo(thread),
        backgroundContent = { SwipeBackground(swipe.dismissDirection, if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) toEnd else toStart) },
        onDismiss = { direction ->
            when (if (direction == SwipeToDismissBoxValue.StartToEnd) toEnd else toStart) {
                SwipeAction.MARK_READ -> {
                    onMarkRead(thread)
                    // The row stays (just read), so bring it back into place.
                    scope.launch { swipe.reset() }
                }
                SwipeAction.DONE -> onMarkDone(thread)
                SwipeAction.NONE -> Unit
            }
        },
    ) {
        var menuOpen by rememberSaveable(thread.id) { mutableStateOf(false) }
        // Reading a thread eases its title from ink to muted rather than snapping.
        val titleColor by animateColorAsState(
            if (thread.unread) colors.ink else colors.inkMuted,
            tween(if (animations) 300 else 0),
            label = "title",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(SoftTokens.RowCorner)
                .background(colors.ground)
                .softPressable { onOpen(thread) }
                // Swipes are invisible to screen readers: the same actions as accessibility actions.
                .semantics {
                    // The dot and the bolder title are visual only: say it.
                    if (thread.unread) stateDescription = unreadState
                    customActions = buildList {
                        if (thread.unread) add(CustomAccessibilityAction(markReadLabel) { onMarkRead(thread); true })
                        add(CustomAccessibilityAction(doneLabel) { onMarkDone(thread); true })
                        add(CustomAccessibilityAction(unsubscribeLabel) { onUnsubscribe(thread); true })
                    }
                }
                .padding(start = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The unread dot, in its own gutter so read and unread titles stay aligned; it pops away when read.
            Box(Modifier.width(20.dp).padding(top = 9.dp), contentAlignment = Alignment.TopCenter) {
                androidx.compose.animation.AnimatedVisibility(
                    thread.unread,
                    enter = if (animations) scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) + fadeIn() else EnterTransition.None,
                    exit = if (animations) scaleOut(tween(180)) + fadeOut(tween(180)) else ExitTransition.None,
                ) {
                    Box(Modifier.size(8.dp).background(colors.thumb, CircleShape))
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                // A flow, so at a large font the repository drops below the pill instead of being squeezed to "ac…".
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    // Waiting on you: why, on the warm "for you" tint. Everything else: what it is, on a quiet surface.
                    if (section == InboxSection.NEEDS_YOU) {
                        SoftPill(stringResource(thread.reason.label), thread.type.icon, colors.fields[0])
                    } else {
                        KindPill(thread)
                    }
                    val where = if (section == InboxSection.NEEDS_YOU) {
                        (thread.repo.fullName + (thread.number?.let { " #$it" } ?: "")).unbreakable()
                    } else {
                        thread.number?.let { "#$it" }
                    }
                    if (where != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (section == InboxSection.NEEDS_YOU) {
                                OwnerAvatar(thread, 18.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(where, style = Soft.type.meta, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(
                    thread.title,
                    style = Soft.type.body.copy(
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = if (thread.unread) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // Which forge (its logo), once more than one is signed in.
                val forge = thread.repo.forge.takeIf { LocalShowForge.current }
                val rest = listOfNotNull(
                    stringResource(thread.reason.label).takeIf { section == InboxSection.OTHERS },
                    relative(thread.updatedAt, nowMillis, abbreviated = true),
                ).joinToString(" · ")
                Text(
                    buildAnnotatedString {
                        forge?.let { appendForge(it); append(" · ") }
                        append(rest)
                    },
                    inlineContent = forge?.let { forgeInlineContent(it, colors.inkMuted) }.orEmpty(),
                    style = Soft.type.meta,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.inbox_more), tint = colors.inkMuted)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = colors.raised,
                ) {
                    if (thread.unread) {
                        DropdownMenuItem(text = { Text(markReadLabel) }, onClick = { menuOpen = false; onMarkRead(thread) })
                    }
                    DropdownMenuItem(text = { Text(doneLabel) }, onClick = { menuOpen = false; onMarkDone(thread) })
                    DropdownMenuItem(text = { Text(unsubscribeLabel) }, onClick = { menuOpen = false; onUnsubscribe(thread) })
                }
            }
        }
    }
}

/** Undo on the ink snackbar: ember where it reads (light), the light theme's deeper ember on the pale dark-theme bar. */
internal fun snackbarAction(colors: SoftColors): Color = if (colors.isDark) SoftLight.accent else colors.thumb

/** Whether swiping [thread] to do this has anything to do: one already read isn't marked read again. */
private fun SwipeAction.appliesTo(thread: NotificationThread): Boolean = when (this) {
    SwipeAction.MARK_READ -> thread.unread
    SwipeAction.DONE -> true
    SwipeAction.NONE -> false
}

/** What a swipe does, shown on a soft tint under the row, on the side the row leaves: mint to mark read, ember for done. */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, action: SwipeAction) {
    val colors = Soft.colors
    val alignment = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> return
    }
    val (icon, tint) = when (action) {
        SwipeAction.MARK_READ -> Icons.Outlined.MarkEmailRead to colors.fields[2]
        SwipeAction.DONE -> Icons.Outlined.Done to colors.fields[0]
        SwipeAction.NONE -> return
    }
    Box(
        Modifier.fillMaxSize().clip(SoftTokens.RowCorner).background(tint).padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink)
    }
}

private val InboxFilter.label: Int
    get() = when (this) {
        InboxFilter.UNREAD -> R.string.inbox_filter_unread
        InboxFilter.PARTICIPATING -> R.string.inbox_filter_participating
        InboxFilter.ALL -> R.string.inbox_filter_all
    }

/**
 * What a thread is about, and where it stands once known: "Merged pull request", "Closed issue". Tinted like the
 * Feed's states: mint while open, lilac once merged, ember once closed.
 */
@Composable
private fun KindPill(thread: NotificationThread) {
    val colors = Soft.colors
    val isPull = thread.type == SubjectType.PULL_REQUEST
    when (thread.state) {
        null -> SoftPill(stringResource(thread.type.label), thread.type.icon, colors.surface)
        SubjectState.OPEN -> SoftPill(
            stringResource(if (isPull) R.string.inbox_state_open_pull else R.string.inbox_state_open_issue), thread.type.icon, colors.fields[2],
        )
        SubjectState.DRAFT -> SoftPill(stringResource(R.string.inbox_state_draft_pull), thread.type.icon, colors.surface)
        SubjectState.MERGED -> SoftPill(stringResource(R.string.inbox_state_merged_pull), Icons.AutoMirrored.Outlined.CallMerge, colors.fields[1])
        SubjectState.CLOSED -> SoftPill(
            stringResource(if (isPull) R.string.inbox_state_closed_pull else R.string.inbox_state_closed_issue),
            if (isPull) Icons.Outlined.Close else Icons.Outlined.CheckCircleOutline,
            colors.fields[0],
        )
        SubjectState.NOT_PLANNED -> SoftPill(stringResource(R.string.inbox_state_not_planned), Icons.Outlined.Block, colors.surface)
    }
}

private val SubjectType.icon: ImageVector
    get() = when (this) {
        SubjectType.ISSUE -> Icons.Outlined.Adjust
        SubjectType.PULL_REQUEST -> Icons.AutoMirrored.Outlined.CallMerge
        SubjectType.RELEASE -> Icons.Outlined.NewReleases
        SubjectType.DISCUSSION -> Icons.Outlined.Forum
        SubjectType.CHECK_SUITE -> Icons.Outlined.PlayCircleOutline
        SubjectType.COMMIT, SubjectType.OTHER -> Icons.Outlined.Notifications
    }

private val SubjectType.label: Int
    get() = when (this) {
        SubjectType.ISSUE -> R.string.inbox_kind_issue
        SubjectType.PULL_REQUEST -> R.string.inbox_kind_pull_request
        SubjectType.RELEASE -> R.string.inbox_kind_release
        SubjectType.DISCUSSION -> R.string.inbox_kind_discussion
        SubjectType.CHECK_SUITE -> R.string.inbox_kind_checks
        SubjectType.COMMIT -> R.string.inbox_kind_commit
        SubjectType.OTHER -> R.string.inbox_kind_other
    }

private val NotificationReason.label: Int
    get() = when (this) {
        NotificationReason.MENTION -> R.string.reason_mention
        NotificationReason.TEAM_MENTION -> R.string.reason_team_mention
        NotificationReason.REVIEW_REQUESTED -> R.string.reason_review_requested
        NotificationReason.ASSIGN -> R.string.reason_assign
        NotificationReason.AUTHOR -> R.string.reason_author
        NotificationReason.COMMENT -> R.string.reason_comment
        NotificationReason.STATE_CHANGE -> R.string.reason_state_change
        NotificationReason.SUBSCRIBED -> R.string.reason_subscribed
        NotificationReason.MANUAL -> R.string.reason_manual
        NotificationReason.CI_ACTIVITY -> R.string.reason_ci_activity
        NotificationReason.SECURITY_ALERT -> R.string.reason_security_alert
        NotificationReason.OTHER -> R.string.reason_other
    }
