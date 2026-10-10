package fr.arthurbrugiere.forgeline.settings

import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.ShareTap
import fr.arthurbrugiere.forgeline.core.model.SwipeAction
import fr.arthurbrugiere.forgeline.core.model.UndoDelay
import fr.arthurbrugiere.forgeline.core.model.StartTab
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.TouchApp
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.ui.rememberNow
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
    GESTURES(R.string.settings_section_gestures, Icons.Outlined.TouchApp),
    READING(R.string.settings_section_reading, Icons.AutoMirrored.Outlined.MenuBook),
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
    val context = LocalContext.current
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
        onChange = viewModel::change,
        // Android keeps the app's language itself: read here, and null where it can't be chosen (before Android 13).
        language = context.takeIf { AppLanguage.canBeChosen }?.let { AppLanguageChoice(AppLanguage.chosen(it)) },
        onLanguageChange = { AppLanguage.choose(context, it) },
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
    nowMillis: Long = rememberNow(measuredAt),
    /** Changes one of the simple choices: it is given the settings and answers them as wanted. */
    onChange: ((UserSettings) -> UserSettings) -> Unit = {},
    /** The app's own language; null where Android doesn't let an app have one. */
    language: AppLanguageChoice? = null,
    onLanguageChange: (String?) -> Unit = {},
) {
    val colors = Soft.colors
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = listBottomPadding()),
            modifier = Modifier.fillMaxSize().sideSafeArea(),
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
            if (section == SettingsSection.APPEARANCE) item {
                ChoiceItem(
                    title = stringResource(R.string.settings_start_tab),
                    options = StartTab.entries.map { stringResource(it.label) },
                    selected = settings.startTab.ordinal,
                    onSelect = { picked -> onChange { it.copy(startTab = StartTab.entries[picked]) } },
                )
            }
            if (section == SettingsSection.APPEARANCE && language != null) item {
                ChoiceItem(
                    title = stringResource(R.string.settings_language),
                    options = listOf(stringResource(R.string.settings_language_system)) + AppLanguage.tags.map(AppLanguage::name),
                    selected = AppLanguage.tags.indexOf(language.tag) + 1,
                    onSelect = { picked -> onLanguageChange(AppLanguage.tags.getOrNull(picked - 1)) },
                )
            }
            if (section == SettingsSection.INBOX) item { InboxCheckItem(settings.inboxCheckInterval, onInboxCheckIntervalChange) }
            if (section == SettingsSection.INBOX) item {
                ChoiceItem(
                    title = stringResource(R.string.settings_undo_delay),
                    options = UndoDelay.entries.map { stringResource(it.label) },
                    selected = settings.undoDelay.ordinal,
                    onSelect = { picked -> onChange { it.copy(undoDelay = UndoDelay.entries[picked]) } },
                )
            }
            if (section == SettingsSection.INBOX) item {
                SwitchItem(
                    title = stringResource(R.string.settings_load_ahead),
                    summary = stringResource(R.string.settings_load_ahead_summary),
                    checked = settings.loadConversationsAhead,
                    onCheckedChange = { on -> onChange { it.copy(loadConversationsAhead = on) } },
                )
            }
            if (section == SettingsSection.INBOX) item {
                SwitchItem(
                    title = stringResource(R.string.settings_read_elsewhere),
                    summary = stringResource(R.string.settings_read_elsewhere_summary),
                    checked = settings.readElsewhereIsDone,
                    onCheckedChange = { on -> onChange { it.copy(readElsewhereIsDone = on) } },
                )
            }
            if (section == SettingsSection.GESTURES) {
                item {
                    ChoiceItem(
                        title = stringResource(R.string.settings_swipe_right),
                        options = SwipeAction.entries.map { stringResource(it.label) },
                        selected = settings.inboxSwipeRight.ordinal,
                        onSelect = { picked -> onChange { it.copy(inboxSwipeRight = SwipeAction.entries[picked]) } },
                    )
                }
                item {
                    ChoiceItem(
                        title = stringResource(R.string.settings_swipe_left),
                        options = SwipeAction.entries.map { stringResource(it.label) },
                        selected = settings.inboxSwipeLeft.ordinal,
                        onSelect = { picked -> onChange { it.copy(inboxSwipeLeft = SwipeAction.entries[picked]) } },
                    )
                }
                item {
                    SwitchItem(
                        title = stringResource(R.string.settings_double_tap),
                        summary = stringResource(R.string.settings_double_tap_summary),
                        checked = settings.doubleTapReaction,
                        onCheckedChange = { on -> onChange { it.copy(doubleTapReaction = on) } },
                    )
                }
                item {
                    SwitchItem(
                        title = stringResource(R.string.settings_swipe_reply),
                        summary = stringResource(R.string.settings_swipe_reply_summary),
                        checked = settings.swipeToReply,
                        onCheckedChange = { on -> onChange { it.copy(swipeToReply = on) } },
                    )
                }
                item {
                    ChoiceItem(
                        title = stringResource(R.string.settings_share_tap),
                        options = ShareTap.entries.map { stringResource(it.label) },
                        selected = settings.shareTap.ordinal,
                        onSelect = { picked -> onChange { it.copy(shareTap = ShareTap.entries[picked]) } },
                    )
                }
            }
            if (section == SettingsSection.READING) {
                item {
                    SwitchItem(
                        title = stringResource(R.string.settings_open_at_unread),
                        summary = stringResource(R.string.settings_open_at_unread_summary),
                        checked = settings.openAtUnread,
                        onCheckedChange = { on -> onChange { it.copy(openAtUnread = on) } },
                    )
                }
                item {
                    SwitchItem(
                        title = stringResource(R.string.settings_reading_marks),
                        summary = stringResource(R.string.settings_reading_marks_summary),
                        checked = settings.readingMarks,
                        onCheckedChange = { on -> onChange { it.copy(readingMarks = on) } },
                    )
                }
            }
            if (section == SettingsSection.TRENDING) item {
                ChoiceItem(
                    title = stringResource(R.string.settings_trending_period),
                    options = TrendingPeriod.entries.map { stringResource(it.settingLabel) },
                    selected = settings.trendingPeriod.ordinal,
                    onSelect = { picked -> onChange { it.copy(trendingPeriod = TrendingPeriod.entries[picked]) } },
                )
            }
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
                // Only GitHub has a trending list of its own. Codeberg's and gitlab.com's are published daily for
                // everyone; once signed in there, the phone can measure them instead, and any other server's too.
                val forges = (session as? SessionState.SignedIn)?.accounts.orEmpty().map { it.forge }.distinct().filter { it.type != ForgeType.GITHUB }
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
                            forge == ForgeInstance.Codeberg || forge == ForgeInstance.GitLab -> stringResource(R.string.settings_trending_measure_codeberg)
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
    SettingsSection.GESTURES -> stringResource(R.string.settings_gestures_summary)
    SettingsSection.READING -> stringResource(R.string.settings_reading_summary)
    SettingsSection.ABOUT -> stringResource(R.string.settings_version_summary, versionName)
}

/** The app's own language as Android keeps it: [tag] is null when it follows the phone's. */
data class AppLanguageChoice(val tag: String?)

private val StartTab.label: Int
    get() = when (this) {
        StartTab.AUTOMATIC -> R.string.settings_start_tab_automatic
        StartTab.INBOX -> R.string.tab_inbox
        StartTab.FEED -> R.string.tab_feed
        StartTab.TRENDING -> R.string.tab_trending
        StartTab.YOU -> R.string.tab_you
    }

private val UndoDelay.label: Int
    get() = when (this) {
        UndoDelay.OFF -> R.string.undo_off
        UndoDelay.SEC_3 -> R.string.undo_3s
        UndoDelay.SEC_5 -> R.string.undo_5s
        UndoDelay.SEC_10 -> R.string.undo_10s
    }

private val SwipeAction.label: Int
    get() = when (this) {
        SwipeAction.MARK_READ -> R.string.inbox_mark_read
        SwipeAction.DONE -> R.string.inbox_mark_done
        SwipeAction.NONE -> R.string.swipe_nothing
    }

private val ShareTap.label: Int
    get() = when (this) {
        ShareTap.SHARE -> R.string.share_tap_shares
        ShareTap.COPY -> R.string.share_tap_copies
    }

private val TrendingPeriod.settingLabel: Int
    get() = when (this) {
        TrendingPeriod.DAILY -> R.string.trending_period_daily
        TrendingPeriod.WEEKLY -> R.string.trending_period_weekly
        TrendingPeriod.MONTHLY -> R.string.trending_period_monthly
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
    ChoiceItem(
        title = stringResource(R.string.settings_inbox_check),
        options = InboxCheckInterval.entries.map { stringResource(it.label) },
        selected = current.ordinal,
        onSelect = { onChange(InboxCheckInterval.entries[it]) },
    )
}

/** A setting that is one among a few: its row says which, and opens the list to pick another. */
@Composable
private fun ChoiceItem(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    SettingRow(title, options.getOrNull(selected), onClick = { choosing = true })
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup()) {
                    options.forEachIndexed { index, option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(SoftTokens.RowCorner)
                                .selectable(selected = index == selected, role = Role.RadioButton) {
                                    choosing = false
                                    onSelect(index)
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(selected = index == selected, onClick = null)
                            Text(option, style = Soft.type.body, modifier = Modifier.padding(start = 16.dp))
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
        FeedKind.PRERELEASES -> R.string.feed_kind_prereleases
        FeedKind.ANNOUNCEMENTS -> R.string.feed_kind_announcements
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
