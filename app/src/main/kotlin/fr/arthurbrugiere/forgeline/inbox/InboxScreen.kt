package fr.arthurbrugiere.forgeline.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.TopLevelScreen
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import kotlinx.coroutines.launch

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
    InboxScreen(
        state = state,
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
    TopLevelScreen(title = stringResource(R.string.tab_inbox)) { padding ->
        if (session == SessionState.SignedOut) {
            EmptyState(
                icon = Icons.Outlined.Inbox,
                title = stringResource(R.string.inbox_signed_out_title),
                body = stringResource(R.string.inbox_signed_out_body),
                actionLabel = stringResource(R.string.sign_in),
                onAction = onSignIn,
                modifier = Modifier.padding(padding),
            )
        }
    }
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
    nowMillis: Long = System.currentTimeMillis(),
) {
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

    TopLevelScreen(title = stringResource(R.string.tab_inbox), modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InboxFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter == state.filter,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(stringResource(filter.label)) },
                    )
                }
            }
            PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                when {
                    !hasThreads && state.error != null -> EmptyState(
                        icon = Icons.Outlined.CloudOff,
                        title = stringResource(R.string.inbox_error_title),
                        body = stringResource(
                            if (state.error is ForgeError.Http && state.error.status == 403) R.string.inbox_error_scope else state.error.message,
                        ),
                        actionLabel = stringResource(R.string.retry),
                        onAction = onRefresh,
                    )
                    // Nothing synced yet: the pull-to-refresh indicator shows progress.
                    !hasThreads && state.syncedAtMillis == null -> Box(Modifier.fillMaxSize())
                    !hasThreads -> EmptyState(
                        icon = if (state.filter == InboxFilter.UNREAD) Icons.Outlined.DoneAll else Icons.Outlined.Inbox,
                        title = stringResource(if (state.filter == InboxFilter.UNREAD) R.string.inbox_caught_up_title else R.string.inbox_empty_title),
                        body = stringResource(if (state.filter == InboxFilter.UNREAD) R.string.inbox_caught_up_body else R.string.inbox_empty_body),
                    )
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        state.groups.forEach { group ->
                            stickyHeader(key = "repo-${group.repo.fullName}") {
                                Text(
                                    group.repo.fullName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(group.threads, key = { "thread-${it.id}" }) { thread ->
                                ThreadRow(thread, nowMillis, onOpen, onMarkRead, onMarkDone, onUnsubscribe, Modifier.animateItem())
                            }
                        }
                    }
                }
            }
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
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val markReadLabel = stringResource(R.string.inbox_mark_read)
    val doneLabel = stringResource(R.string.inbox_mark_done)
    val unsubscribeLabel = stringResource(R.string.inbox_unsubscribe)
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
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
        ListItem(
            modifier = Modifier
                .clickable { onOpen(thread) }
                // Swipes are invisible to screen readers: the same actions as accessibility actions.
                .semantics {
                    customActions = buildList {
                        if (thread.unread) add(CustomAccessibilityAction(markReadLabel) { onMarkRead(thread); true })
                        add(CustomAccessibilityAction(doneLabel) { onMarkDone(thread); true })
                        add(CustomAccessibilityAction(unsubscribeLabel) { onUnsubscribe(thread); true })
                    }
                },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = {
                Box {
                    Icon(thread.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (thread.unread) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            },
            headlineContent = {
                Text(
                    thread.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (thread.unread) FontWeight.SemiBold else null,
                )
            },
            supportingContent = {
                Text(
                    listOfNotNull(
                        thread.number?.let { "#$it" },
                        stringResource(thread.reason.label),
                        relative(thread.updatedAt, nowMillis),
                    ).joinToString(" · "),
                )
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (thread.unread) {
                            DropdownMenuItem(text = { Text(markReadLabel) }, onClick = { menuOpen = false; onMarkRead(thread) })
                        }
                        DropdownMenuItem(text = { Text(doneLabel) }, onClick = { menuOpen = false; onMarkDone(thread) })
                        DropdownMenuItem(text = { Text(unsubscribeLabel) }, onClick = { menuOpen = false; onUnsubscribe(thread) })
                    }
                }
            },
        )
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val (icon, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Icons.Outlined.MarkEmailRead to Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Icons.Outlined.Done to Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> return
    }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
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
