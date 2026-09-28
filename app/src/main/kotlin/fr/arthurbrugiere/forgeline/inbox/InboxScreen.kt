package fr.arthurbrugiere.forgeline.inbox

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
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.SubjectType
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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

@Composable
fun InboxRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenThread: (NotificationThread) -> Unit,
) {
    if (session !is SessionState.SignedIn) {
        InboxSignedOut(session, onSignIn)
        return
    }
    // Keyed by account so switching accounts never shows the previous inbox's state.
    val viewModel = hiltViewModel<InboxViewModel>(key = session.account.id)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notifications = rememberNotificationPrompt()
    InboxScreen(
        state = state,
        notificationPrompt = notifications.prompt.takeIf { state.backgroundChecks },
        onAllowNotifications = notifications.onAllow,
        onSelectFilter = viewModel::selectFilter,
        onRefresh = viewModel::refresh,
        onOpen = { thread ->
            viewModel.opened(thread)
            onOpenThread(thread)
        },
        onMarkRead = viewModel::markRead,
        onMarkDone = viewModel::markDone,
        onUnsubscribe = viewModel::unsubscribe,
        onErrorShown = viewModel::errorShown,
        onActionFailureShown = viewModel::actionFailureShown,
    )
}

@Composable
private fun InboxSignedOut(session: SessionState, onSignIn: () -> Unit) {
    val colors = Soft.colors
    Column(Modifier.fillMaxSize().background(colors.ground), horizontalAlignment = Alignment.CenterHorizontally) {
        InboxHeader(filter = null, onSelectFilter = {})
        if (session == SessionState.SignedOut) {
            SoftNotice(
                stringResource(R.string.inbox_signed_out_title),
                stringResource(R.string.inbox_signed_out_body),
                action = stringResource(R.string.sign_in),
                onAction = onSignIn,
            )
        }
    }
}

/** The Inbox's tinted field: the title, search and, once signed in, the filter switch (each filter its own tint). */
@Composable
private fun InboxHeader(filter: InboxFilter?, onSelectFilter: (InboxFilter) -> Unit) {
    val colors = Soft.colors
    val openSearch = LocalOpenSearch.current
    SoftHeader(
        tint = colors.fields[filter?.ordinal ?: 0],
        title = stringResource(R.string.tab_inbox),
        actions = {
            if (openSearch != null) {
                IconButton(onClick = openSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search), tint = colors.ink)
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
    nowMillis: Long = System.currentTimeMillis(),
) {
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
    LaunchedEffect(state.actionFailed) {
        if (state.actionFailed) {
            snackbar.showSnackbar(actionFailed)
            onActionFailureShown()
        }
    }

    val listState = rememberLazyListState()
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
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
            ) {
                item(key = "header", contentType = "header") { InboxHeader(state.filter, onSelectFilter) }
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
                        stickyHeader(key = "repo-${group.repo.fullName}", contentType = "repo") {
                            RepoHeading(group.repo.owner, group.repo.name, group.threads.count { it.unread })
                        }
                        items(group.threads, key = { "thread-${it.id}" }, contentType = { "thread" }) { thread ->
                            ThreadRow(
                                thread, nowMillis, onOpen, onMarkRead, onMarkDone, onUnsubscribe,
                                Modifier.widthIn(max = SoftTokens.MaxReadingWidth).animateItem(),
                            )
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

/** A repository's name over its threads, pinned while they scroll; the unread count as a soft tag. */
@Composable
private fun RepoHeading(owner: String, name: String, unread: Int) {
    val colors = Soft.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.ground)
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.inkMuted)) { append("$owner/") }
                append(name)
            },
            style = Soft.type.control.copy(fontSize = 17.sp, lineHeight = 22.sp),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            SoftTag("$unread", background = colors.fields[0])
        }
    }
}

@Composable
private fun ThreadRow(
    thread: NotificationThread,
    nowMillis: Long,
    onOpen: (NotificationThread) -> Unit,
    onMarkRead: (NotificationThread) -> Unit,
    onMarkDone: (NotificationThread) -> Unit,
    onUnsubscribe: (NotificationThread) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val markReadLabel = stringResource(R.string.inbox_mark_read)
    val doneLabel = stringResource(R.string.inbox_mark_done)
    val unsubscribeLabel = stringResource(R.string.inbox_unsubscribe)
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        enableDismissFromStartToEnd = thread.unread,
        backgroundContent = { SwipeBackground(swipe.dismissDirection) },
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onMarkRead(thread)
                    // The row stays (just read), so bring it back into place.
                    scope.launch { swipe.reset() }
                }
                SwipeToDismissBoxValue.EndToStart -> onMarkDone(thread)
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
    ) {
        var menuOpen by rememberSaveable(thread.id) { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(SoftTokens.RowCorner)
                .background(colors.ground)
                .softPressable { onOpen(thread) }
                // Swipes are invisible to screen readers: the same actions as accessibility actions.
                .semantics {
                    customActions = buildList {
                        if (thread.unread) add(CustomAccessibilityAction(markReadLabel) { onMarkRead(thread); true })
                        add(CustomAccessibilityAction(doneLabel) { onMarkDone(thread); true })
                        add(CustomAccessibilityAction(unsubscribeLabel) { onUnsubscribe(thread); true })
                    }
                }
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Box(Modifier.size(40.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(thread.type.icon, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp))
                }
                if (thread.unread) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(12.dp)
                            .background(colors.ground, CircleShape)
                            .padding(2.dp)
                            .background(colors.thumb, CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    thread.title,
                    style = Soft.type.body.copy(fontWeight = if (thread.unread) FontWeight.Medium else FontWeight.Normal),
                    color = if (thread.unread) colors.ink else colors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        thread.number?.let { "#$it" },
                        stringResource(thread.reason.label),
                        relative(thread.updatedAt, nowMillis),
                    ).joinToString(" · "),
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

/** What a swipe does, shown on a soft tint under the row: mint to mark read, ember for done. */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val colors = Soft.colors
    val (icon, alignment, tint) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(Icons.Outlined.MarkEmailRead, Alignment.CenterStart, colors.fields[2])
        SwipeToDismissBoxValue.EndToStart -> Triple(Icons.Outlined.Done, Alignment.CenterEnd, colors.fields[0])
        SwipeToDismissBoxValue.Settled -> return
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

private val SubjectType.icon: ImageVector
    get() = when (this) {
        SubjectType.ISSUE -> Icons.Outlined.Adjust
        SubjectType.PULL_REQUEST -> Icons.AutoMirrored.Outlined.CallMerge
        SubjectType.RELEASE -> Icons.Outlined.NewReleases
        SubjectType.DISCUSSION -> Icons.Outlined.Forum
        SubjectType.CHECK_SUITE -> Icons.Outlined.PlayCircleOutline
        SubjectType.COMMIT, SubjectType.OTHER -> Icons.Outlined.Notifications
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
