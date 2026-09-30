package fr.arthurbrugiere.forgeline.settings

import java.time.Instant
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.foundation.layout.height
import fr.arthurbrugiere.forgeline.you.NavigationRow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeIcon
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
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
import fr.arthurbrugiere.forgeline.core.model.Account
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
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

const val SOURCE_CODE_URL = "https://github.com/RoiArthurB/forgeline"

/** Settings' pages, each opened from the main list. */
enum class SettingsSection(@StringRes val title: Int, val icon: ImageVector) {
    ACCOUNTS(R.string.settings_section_account, Icons.Outlined.Person),
    APPEARANCE(R.string.settings_section_appearance, Icons.Outlined.Palette),
    INBOX(R.string.settings_section_notifications, Icons.Outlined.Notifications),
    FEED(R.string.settings_section_feed, Icons.Outlined.DynamicFeed),
    TRENDING(R.string.settings_section_trending, Icons.AutoMirrored.Outlined.TrendingUp),
    ABOUT(R.string.settings_section_about, Icons.Outlined.Info),
}

@Composable
fun SettingsRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onSignOut: (Account) -> Unit,
    onBack: () -> Unit,
    onOpenCredits: () -> Unit,
    section: SettingsSection? = null,
    onOpenSection: (SettingsSection) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val measuredAt by viewModel.measuredAt.collectAsStateWithLifecycle()
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
        onSeparateInboxChange = viewModel::setSeparateInboxPerForge,
        onOpenCredits = onOpenCredits,
        onOpenSourceCode = { uriHandler.openUri(SOURCE_CODE_URL) },
        onBack = onBack,
        section = section,
        onOpenSection = onOpenSection,
        measuredAt = measuredAt,
        onTrendingMeasuredChange = viewModel::setTrendingMeasured,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    session: SessionState,
    onSignIn: () -> Unit,
    onSignOut: (Account) -> Unit,
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
    onSeparateInboxChange: (Boolean) -> Unit = {},
    /** The page shown; null is the main list of pages. */
    section: SettingsSection? = null,
    onOpenSection: (SettingsSection) -> Unit = {},
    measuredAt: Map<String, Long> = emptyMap(),
    onTrendingMeasuredChange: (host: String, measured: Boolean) -> Unit = { _, _ -> },
    nowMillis: Long = System.currentTimeMillis(),
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
                    title = stringResource(section?.title ?: R.string.settings_title),
                    onBack = onBack,
                    backDescription = stringResource(R.string.navigate_up),
                )
            }
            if (section == null) {
                item { Spacer(Modifier.height(8.dp)) }
                items(SettingsSection.entries, key = { it.name }) { page ->
                    NavigationRow(page.icon, stringResource(page.title), onClick = { onOpenSection(page) }, summary = page.summary(session, settings, versionName))
                }
            }
            if (section == SettingsSection.ACCOUNTS && session != SessionState.Loading) {
                when (session) {
                    is SessionState.SignedIn -> {
                        // Every signed-in account, one per forge or more, each signed out on its own.
                        items(session.accounts, key = { "account-${it.id}" }) { AccountItem(it, onSignOut) }
                        item(key = "add-account") {
                            SettingRow(stringResource(R.string.settings_add_account), stringResource(R.string.settings_add_account_summary), onClick = onSignIn)
                        }
                    }
                    else -> item { SettingRow(stringResource(R.string.sign_in), stringResource(R.string.settings_signed_out_summary), onClick = onSignIn) }
                }
            }
            if (section == SettingsSection.APPEARANCE) item {
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
            if (section == SettingsSection.APPEARANCE) item {
                SwitchItem(
                    title = stringResource(R.string.settings_amoled),
                    summary = stringResource(R.string.settings_amoled_summary),
                    checked = settings.amoledBlack,
                    onCheckedChange = onAmoledBlackChange,
                )
            }
            if (section == SettingsSection.INBOX) item { InboxCheckItem(settings.inboxCheckInterval, onInboxCheckIntervalChange) }
            // Only meaningful with several accounts: one list, or one tab per account.
            if (section == SettingsSection.INBOX && (session as? SessionState.SignedIn)?.accounts.orEmpty().size > 1) {
                item {
                    SwitchItem(
                        title = stringResource(R.string.settings_separate_inbox),
                        summary = stringResource(R.string.settings_separate_inbox_summary),
                        checked = settings.separateInboxPerForge,
                        onCheckedChange = onSeparateInboxChange,
                    )
                }
            }
            if (section == SettingsSection.FEED) item { FeedKindsItem(settings.feedKinds, onFeedKindChange) }
            if (section == SettingsSection.TRENDING) {
                // Forgejo forges have no trending list of their own: Codeberg's is published daily, any other
                // server's can be measured here. GitHub's comes from GitHub.
                val forges = (session as? SessionState.SignedIn)?.accounts.orEmpty().map { it.forge }.distinct().filter { it.type == ForgeType.FORGEJO }
                if (forges.isEmpty()) {
                    item { SoftNotice(stringResource(R.string.settings_trending_none_title), stringResource(R.string.settings_trending_none_body)) }
                }
                items(forges, key = { "measure-${it.host}" }) { forge ->
                    val measured = forge.host in settings.measuredTrending
                    val last = measuredAt[forge.host]?.takeIf { measured }?.let { relative(Instant.ofEpochMilli(it), nowMillis) }
                    SwitchItem(
                        title = stringResource(R.string.settings_trending_measure, forge.displayName),
                        summary = when {
                            last != null -> stringResource(R.string.settings_trending_measured_at, last)
                            forge == ForgeInstance.Codeberg -> stringResource(R.string.settings_trending_measure_codeberg)
                            else -> stringResource(R.string.settings_trending_measure_server)
                        },
                        checked = measured,
                        onCheckedChange = { onTrendingMeasuredChange(forge.host, it) },
                        leading = { ForgeIcon(forge, size = 24.dp, tint = colors.ink) },
                    )
                }
                if (forges.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.settings_trending_how),
                            style = Soft.type.secondary,
                            color = colors.inkMuted,
                            modifier = SettingModifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            if (section == SettingsSection.ABOUT) {
                item { SettingRow(stringResource(R.string.settings_credits), stringResource(R.string.settings_credits_summary), onClick = onOpenCredits) }
                item { SettingRow(stringResource(R.string.settings_source_code), SOURCE_CODE_URL.removePrefix("https://"), onClick = onOpenSourceCode) }
                item { SettingRow(stringResource(R.string.settings_version), versionName) }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
    }
}

/** What a page holds now, under its name in the main list. */
@Composable
private fun SettingsSection.summary(session: SessionState, settings: UserSettings, versionName: String): String? = when (this) {
    SettingsSection.ACCOUNTS -> when (session) {
        is SessionState.SignedIn -> session.accounts.joinToString { "@${it.user.login}" }
        SessionState.SignedOut -> stringResource(R.string.settings_signed_out_summary)
        SessionState.Loading -> null
    }
    SettingsSection.APPEARANCE -> stringResource(settings.themeMode.label)
    SettingsSection.INBOX -> stringResource(R.string.settings_inbox_check_summary, stringResource(settings.inboxCheckInterval.label))
    SettingsSection.FEED -> stringResource(R.string.settings_feed_kinds_summary, settings.feedKinds.size, FeedKind.entries.size)
    SettingsSection.TRENDING -> settings.measuredTrending.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.settings_trending_summary_measured, it.sorted().joinToString()) }
        ?: stringResource(R.string.settings_trending_summary)
    SettingsSection.ABOUT -> stringResource(R.string.settings_version_summary, versionName)
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
private fun AccountItem(account: Account, onSignOut: (Account) -> Unit) {
    val colors = Soft.colors
    var confirming by rememberSaveable(account.id) { mutableStateOf(false) }
    val login = account.user.login
    SettingRow(
        title = "@$login",
        // Two accounts can share a login: the forge's name says which is which, the logo on the avatar echoes it.
        summary = account.forge.displayName,
        leading = {
            Box {
                Avatar(account.user.avatarUrl, login, size = 40.dp, placeholderColor = colors.surface, placeholderContentColor = colors.inkMuted)
                ForgeIcon(
                    account.forge,
                    size = 14.dp,
                    tint = colors.ink,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp)
                        .background(colors.raised, CircleShape).padding(3.dp),
                )
            }
        },
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
                    onSignOut(account)
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
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = Soft.colors
    SettingRow(
        title = title,
        summary = summary,
        leading = leading,
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
                    uncheckedBorderColor = Color.Transparent,
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
