package fr.arthurbrugiere.forgeline.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.BuildConfig
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSectionTitle
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

const val SOURCE_CODE_URL = "https://github.com/RoiArthurB/forgeline"

@Composable
fun SettingsRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    onOpenCredits: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    SettingsScreen(
        session = session,
        onSignIn = onSignIn,
        onSignOut = onSignOut,
        settings = settings,
        versionName = BuildConfig.VERSION_NAME,
        onThemeModeChange = viewModel::setThemeMode,
        onAmoledBlackChange = viewModel::setAmoledBlack,
        onInboxCheckIntervalChange = viewModel::setInboxCheckInterval,
        onFeedKindChange = viewModel::setFeedKindShown,
        onOpenCredits = onOpenCredits,
        onOpenSourceCode = { uriHandler.openUri(SOURCE_CODE_URL) },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    session: SessionState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    settings: UserSettings,
    versionName: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAmoledBlackChange: (Boolean) -> Unit,
    onOpenCredits: () -> Unit,
    onOpenSourceCode: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onInboxCheckIntervalChange: (InboxCheckInterval) -> Unit = {},
    onFeedKindChange: (FeedKind, Boolean) -> Unit = { _, _ -> },
) {
    val colors = Soft.colors
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = listBottomPadding()),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        ) {
            item {
                SoftHeader(
                    tint = colors.fields[2],
                    title = stringResource(R.string.settings_title),
                    onBack = onBack,
                    backDescription = stringResource(R.string.navigate_up),
                )
            }
            if (session != SessionState.Loading) {
                item { SoftSectionTitle(stringResource(R.string.settings_section_account)) }
                item { AccountItem(session, onSignIn, onSignOut) }
            }
            item { SoftSectionTitle(stringResource(R.string.settings_section_appearance)) }
            item {
                Column(SettingModifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.settings_theme), style = Soft.type.body, color = colors.ink)
                    SoftSwitch(
                        options = ThemeMode.entries.map { stringResource(it.label) },
                        selected = settings.themeMode.ordinal,
                        onSelect = { onThemeModeChange(ThemeMode.entries[it]) },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            item {
                SwitchItem(
                    title = stringResource(R.string.settings_amoled),
                    summary = stringResource(R.string.settings_amoled_summary),
                    checked = settings.amoledBlack,
                    onCheckedChange = onAmoledBlackChange,
                )
            }
            item { SoftSectionTitle(stringResource(R.string.settings_section_notifications)) }
            item { InboxCheckItem(settings.inboxCheckInterval, onInboxCheckIntervalChange) }
            item { SoftSectionTitle(stringResource(R.string.settings_section_feed)) }
            item { FeedKindsItem(settings.feedKinds, onFeedKindChange) }
            item { SoftSectionTitle(stringResource(R.string.settings_section_about)) }
            item { SettingRow(stringResource(R.string.settings_credits), stringResource(R.string.settings_credits_summary), onClick = onOpenCredits) }
            item { SettingRow(stringResource(R.string.settings_source_code), SOURCE_CODE_URL.removePrefix("https://"), onClick = onOpenSourceCode) }
            item { SettingRow(stringResource(R.string.settings_version), versionName) }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
    }
}

private val SettingModifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)

/** One setting: its name, its current value underneath, and optionally something on the right. */
@Composable
private fun SettingRow(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Soft.colors
    Row(
        SettingModifier
            .then(if (onClick != null) Modifier.softPressable(onClick = onClick) else Modifier)
            .then(modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Soft.type.body, color = colors.ink)
            summary?.let { Text(it, style = Soft.type.secondary, color = colors.inkMuted) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun AccountItem(session: SessionState, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    val colors = Soft.colors
    when (session) {
        SessionState.Loading -> Unit
        SessionState.SignedOut -> SettingRow(stringResource(R.string.sign_in), stringResource(R.string.settings_signed_out_summary), onClick = onSignIn)
        is SessionState.SignedIn -> {
            var confirming by rememberSaveable { mutableStateOf(false) }
            val login = session.account.user.login
            SettingRow(
                title = "@$login",
                summary = session.account.forge.host,
                leading = { Avatar(session.account.user.avatarUrl, login, size = 40.dp, placeholderColor = colors.surface, placeholderContentColor = colors.inkMuted) },
                trailing = { SoftTonalButton(stringResource(R.string.sign_out), onClick = { confirming = true }) },
            )
            if (confirming) {
                AlertDialog(
                    onDismissRequest = { confirming = false },
                    title = { Text(stringResource(R.string.sign_out_confirm_title, login)) },
                    text = { Text(stringResource(R.string.sign_out_confirm_body)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirming = false
                            onSignOut()
                        }) { Text(stringResource(R.string.sign_out)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
                    },
                    shape = RoundedCornerShape(28.dp),
                    containerColor = colors.raised,
                )
            }
        }
    }
}

@Composable
private fun InboxCheckItem(current: InboxCheckInterval, onChange: (InboxCheckInterval) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    SettingRow(stringResource(R.string.settings_inbox_check), stringResource(current.label), onClick = { choosing = true })
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.settings_inbox_check)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    InboxCheckInterval.entries.forEach { interval ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(SoftTokens.RowCorner)
                                .selectable(selected = interval == current, role = Role.RadioButton) {
                                    choosing = false
                                    onChange(interval)
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(selected = interval == current, onClick = null)
                            Text(stringResource(interval.label), style = Soft.type.body, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = Soft.colors.raised,
        )
    }
}

@Composable
private fun FeedKindsItem(shown: Set<FeedKind>, onChange: (FeedKind, Boolean) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    SettingRow(
        stringResource(R.string.settings_feed_kinds),
        stringResource(R.string.settings_feed_kinds_summary, shown.size, FeedKind.entries.size),
        onClick = { choosing = true },
    )
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.settings_feed_kinds)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FeedKind.entries.forEach { kind ->
                        val checked = kind in shown
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(SoftTokens.RowCorner)
                                .toggleable(value = checked, role = Role.Checkbox) { onChange(kind, it) }
                                .padding(vertical = 8.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(stringResource(kind.label), style = Soft.type.body, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.done)) }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = Soft.colors.raised,
        )
    }
}

private val FeedKind.label: Int
    get() = when (this) {
        FeedKind.STARS -> R.string.feed_kind_stars
        FeedKind.FORKS -> R.string.feed_kind_forks
        FeedKind.NEW_REPOS -> R.string.feed_kind_new_repos
        FeedKind.RELEASES -> R.string.feed_kind_releases
        FeedKind.ISSUES_OPENED -> R.string.feed_kind_issues_opened
        FeedKind.ISSUES_CLOSED -> R.string.feed_kind_issues_closed
        FeedKind.PRS_OPENED -> R.string.feed_kind_prs_opened
        FeedKind.PRS_CLOSED -> R.string.feed_kind_prs_closed
        FeedKind.COMMENTS -> R.string.feed_kind_comments
        FeedKind.REVIEWS -> R.string.feed_kind_reviews
        FeedKind.PUSHES -> R.string.feed_kind_pushes
        FeedKind.BRANCHES -> R.string.feed_kind_branches
        FeedKind.MEMBERS -> R.string.feed_kind_members
    }

private val InboxCheckInterval.label: Int
    get() = when (this) {
        InboxCheckInterval.OFF -> R.string.interval_off
        InboxCheckInterval.MIN_15 -> R.string.interval_15m
        InboxCheckInterval.MIN_30 -> R.string.interval_30m
        InboxCheckInterval.HOUR_1 -> R.string.interval_1h
        InboxCheckInterval.HOUR_3 -> R.string.interval_3h
        InboxCheckInterval.HOUR_6 -> R.string.interval_6h
    }

/** A setting that switches on and off; the whole row toggles it. */
@Composable
private fun SwitchItem(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = Soft.colors
    SettingRow(
        title = title,
        summary = summary,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.thumb,
                    checkedThumbColor = colors.onThumb,
                    checkedBorderColor = colors.thumb,
                    uncheckedTrackColor = colors.surface,
                    uncheckedThumbColor = colors.inkMuted,
                    uncheckedBorderColor = colors.inkMuted,
                ),
            )
        },
    )
}

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
