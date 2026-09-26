package fr.arthurbrugiere.forgeline.trending

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.format.parseHexColor
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.TopLevelScreen
import java.text.NumberFormat

@Composable
fun TrendingRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    viewModel: TrendingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signedIn = session is SessionState.SignedIn
    TrendingScreen(
        state = state,
        onPeriodChange = viewModel::selectPeriod,
        onRefresh = viewModel::refresh,
        onToggleStar = { repo -> if (signedIn) viewModel.toggleStar(repo) else onSignIn() },
        onOpenRepo = onOpenRepo,
        onErrorShown = viewModel::errorShown,
        onStarFailureShown = viewModel::starFailureShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendingScreen(
    state: TrendingUiState,
    onPeriodChange: (TrendingPeriod) -> Unit,
    onRefresh: () -> Unit,
    onToggleStar: (RepoId) -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onErrorShown: () -> Unit,
    onStarFailureShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    val starFailed = stringResource(R.string.trending_star_failed)
    val hasItems = state.items.isNotEmpty()

    LaunchedEffect(state.error, hasItems) {
        if (state.error != null && hasItems) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }
    LaunchedEffect(state.starFailed) {
        if (state.starFailed) {
            snackbar.showSnackbar(starFailed)
            onStarFailureShown()
        }
    }

    TopLevelScreen(
        title = stringResource(R.string.tab_trending),
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PeriodSelector(state.period, onPeriodChange, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            when {
                !hasItems && state.error != null -> EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.trending_error_title),
                    body = stringResource(state.error.message),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRefresh,
                )
                !hasItems -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.updatedAtMillis?.let { updatedAt ->
                            item(key = "updated") { UpdatedAt(updatedAt, nowMillis) }
                        }
                        items(state.items, key = { it.repo.id.fullName }) { item ->
                            TrendingCard(
                                item,
                                state.period,
                                onToggleStar = { onToggleStar(item.repo.id) },
                                onOpen = { onOpenRepo(item.repo.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodSelector(selected: TrendingPeriod, onSelect: (TrendingPeriod) -> Unit, modifier: Modifier = Modifier) {
    val periods = TrendingPeriod.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        periods.forEachIndexed { index, period ->
            SegmentedButton(
                selected = period == selected,
                onClick = { onSelect(period) },
                shape = SegmentedButtonDefaults.itemShape(index, periods.size),
                label = { Text(stringResource(period.label)) },
            )
        }
    }
}

@Composable
private fun UpdatedAt(updatedAtMillis: Long, nowMillis: Long) {
    val relative = DateUtils.getRelativeTimeSpanString(updatedAtMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
    Text(
        stringResource(R.string.trending_updated, relative),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TrendingCard(item: TrendingItem, period: TrendingPeriod, onToggleStar: () -> Unit, onOpen: () -> Unit) {
    val repo = item.repo
    val starred = item.starred == true
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("${repo.id.owner} / ") }
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(repo.id.name) }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                val label = stringResource(if (starred) R.string.trending_unstar else R.string.trending_star, repo.id.fullName)
                IconToggleButton(
                    checked = starred,
                    onCheckedChange = { onToggleStar() },
                    modifier = Modifier.semantics { contentDescription = label },
                ) {
                    Icon(
                        if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = null,
                        tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repo.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    repo.language?.let { language ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            parseHexColor(repo.languageColor)?.let { color ->
                                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                            }
                            MetaText(language)
                        }
                    }
                    MetaStat(Icons.Outlined.StarBorder, compactCount(repo.stars), stringResource(R.string.trending_stars, repo.stars))
                    MetaStat(Icons.AutoMirrored.Outlined.CallSplit, compactCount(repo.forks), stringResource(R.string.trending_forks, repo.forks))
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(period.gainedLabel, NumberFormat.getIntegerInstance().format(repo.periodStars)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (repo.builtBy.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetaText(stringResource(R.string.trending_built_by))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repo.builtBy.take(5).forEach { Avatar(it.avatarUrl, it.login, size = 20.dp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaStat(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        MetaText(text)
    }
}

@Composable
private fun MetaText(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val TrendingPeriod.label: Int
    get() = when (this) {
        TrendingPeriod.DAILY -> R.string.trending_period_daily
        TrendingPeriod.WEEKLY -> R.string.trending_period_weekly
        TrendingPeriod.MONTHLY -> R.string.trending_period_monthly
    }

private val TrendingPeriod.gainedLabel: Int
    get() = when (this) {
        TrendingPeriod.DAILY -> R.string.trending_gained_daily
        TrendingPeriod.WEEKLY -> R.string.trending_gained_weekly
        TrendingPeriod.MONTHLY -> R.string.trending_gained_monthly
    }

private val ForgeError.message: Int
    get() = when (this) {
        ForgeError.Network -> R.string.trending_error_offline
        is ForgeError.RateLimited -> R.string.trending_error_rate_limited
        else -> R.string.trending_error_unknown
    }
