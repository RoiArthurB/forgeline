package fr.arthurbrugiere.forgeline.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.BuildConfig
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar

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
        onDynamicColorChange = viewModel::setDynamicColor,
        onAmoledBlackChange = viewModel::setAmoledBlack,
        onInboxCheckIntervalChange = viewModel::setInboxCheckInterval,
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
    onDynamicColorChange: (Boolean) -> Unit,
    onAmoledBlackChange: (Boolean) -> Unit,
    onOpenCredits: () -> Unit,
    onOpenSourceCode: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onInboxCheckIntervalChange: (InboxCheckInterval) -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            if (session != SessionState.Loading) {
                item { SectionHeader(stringResource(R.string.settings_section_account)) }
                item { AccountItem(session, onSignIn, onSignOut) }
            }
            item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_theme)) },
                    supportingContent = {
                        ThemeModeSelector(
                            selected = settings.themeMode,
                            onSelect = onThemeModeChange,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    },
                )
            }
            item {
                SwitchItem(
                    title = stringResource(R.string.settings_dynamic_color),
                    summary = stringResource(R.string.settings_dynamic_color_summary),
                    checked = settings.dynamicColor,
                    onCheckedChange = onDynamicColorChange,
                )
            }
            item {
                SwitchItem(
                    title = stringResource(R.string.settings_amoled),
                    summary = stringResource(R.string.settings_amoled_summary),
                    checked = settings.amoledBlack,
                    onCheckedChange = onAmoledBlackChange,
                )
            }
            item { SectionHeader(stringResource(R.string.settings_section_notifications)) }
            item { InboxCheckItem(settings.inboxCheckInterval, onInboxCheckIntervalChange) }
            item { SectionHeader(stringResource(R.string.settings_section_about)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_credits)) },
                    supportingContent = { Text(stringResource(R.string.settings_credits_summary)) },
                    modifier = Modifier.clickable(onClick = onOpenCredits),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_source_code)) },
                    supportingContent = { Text(SOURCE_CODE_URL.removePrefix("https://")) },
                    modifier = Modifier.clickable(onClick = onOpenSourceCode),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    supportingContent = { Text(versionName) },
                )
            }
        }
    }
}

@Composable
private fun AccountItem(session: SessionState, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    when (session) {
        SessionState.Loading -> Unit
        SessionState.SignedOut -> ListItem(
            headlineContent = { Text(stringResource(R.string.sign_in)) },
            supportingContent = { Text(stringResource(R.string.settings_signed_out_summary)) },
            modifier = Modifier.clickable(onClick = onSignIn),
        )
        is SessionState.SignedIn -> {
            var confirming by rememberSaveable { mutableStateOf(false) }
            val login = session.account.user.login
            ListItem(
                leadingContent = { Avatar(session.account.user.avatarUrl, login, size = 40.dp) },
                headlineContent = { Text("@$login") },
                supportingContent = { Text(session.account.forge.host) },
                trailingContent = {
                    TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.sign_out)) }
                },
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
                )
            }
        }
    }
}

@Composable
private fun InboxCheckItem(current: InboxCheckInterval, onChange: (InboxCheckInterval) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_inbox_check)) },
        supportingContent = { Text(stringResource(current.label)) },
        modifier = Modifier.clickable { choosing = true },
    )
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
                                .selectable(selected = interval == current, role = Role.RadioButton) {
                                    choosing = false
                                    onChange(interval)
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(selected = interval == current, onClick = null)
                            Text(stringResource(interval.label), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
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

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchItem(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(stringResource(mode.label)) },
            )
        }
    }
}

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
