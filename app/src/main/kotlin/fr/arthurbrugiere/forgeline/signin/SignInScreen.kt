package fr.arthurbrugiere.forgeline.signin

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeIcon
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.you.NavigationRow
import kotlinx.coroutines.launch

@Composable
fun SignInRoute(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.step) {
        when (val step = state.step) {
            SignInStep.SignedIn -> onSignedIn()
            // The forge's approval page opens once; "Open again" is there if the tab was closed.
            is SignInStep.AwaitingBrowser -> openUrl(step.authorizationUrl)
            else -> Unit
        }
    }

    SignInScreen(
        state = state,
        onSelectForge = viewModel::selectForge,
        onChooseForge = viewModel::chooseAnotherForge,
        onHostChange = viewModel::setHost,
        onClientIdChange = viewModel::setOauthClientId,
        onSelectGitLabServer = viewModel::selectGitLabServer,
        onStartDeviceFlow = viewModel::startDeviceFlow,
        onStartBrowserSignIn = viewModel::startBrowserSignIn,
        onContinueOnGitHub = { code, uri ->
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("GitHub code", code))) }
            openUrl(uri)
        },
        onSubmitToken = viewModel::signInWithToken,
        onSubmitPassword = viewModel::signInWithPassword,
        onOpenUrl = openUrl,
        onCancel = viewModel::cancel,
        onDismissError = viewModel::dismissError,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    state: SignInUiState,
    onStartDeviceFlow: () -> Unit,
    onContinueOnGitHub: (code: String, verificationUri: String) -> Unit,
    onSubmitToken: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectForge: (SignInForge) -> Unit = {},
    onHostChange: (String) -> Unit = {},
    onClientIdChange: (String) -> Unit = {},
    onSelectGitLabServer: (Boolean) -> Unit = {},
    onStartBrowserSignIn: () -> Unit = {},
    onSubmitPassword: (identifier: String, password: String) -> Unit = { _, _ -> },
    onChooseForge: () -> Unit = {},
) {
    val colors = Soft.colors
    val forgeName = state.forgeName
    // A forge's form goes back to the list of forges, which is where signing in started.
    BackHandler(enabled = !state.choosingForge, onBack = onChooseForge)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.ground)
            // The token field scrolls above the keyboard (edge-to-edge doesn't resize the window for it).
            .sideSafeArea()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = listBottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SoftHeader(
            title = when {
                state.choosingForge -> stringResource(R.string.sign_in)
                forgeName.isBlank() -> stringResource(R.string.sign_in_headline_other)
                else -> stringResource(R.string.sign_in_headline, forgeName)
            },
            onBack = if (state.choosingForge) onBack else onChooseForge,
            backDescription = stringResource(R.string.navigate_up),
        ) {
            // The field says one thing: where a sign-in goes. What the forge will ask is said where it is asked for.
            Text(
                if (state.choosingForge) {
                    stringResource(R.string.sign_in_privacy_any)
                } else {
                    stringResource(R.string.sign_in_privacy, forgeName.ifBlank { stringResource(R.string.sign_in_forge_other) })
                },
                style = Soft.type.body,
                color = Soft.colors.inkMuted,
                modifier = Modifier.widthIn(max = SoftTokens.MaxMeasure),
            )
        }
        if (state.choosingForge) {
            ForgeList(onSelectForge)
            return@Column
        }
        Column(
            Modifier.widthIn(max = SoftTokens.MaxMeasure).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val step = state.step) {
                SignInStep.Verifying, SignInStep.SignedIn -> Verifying(forgeName)
                is SignInStep.AwaitingAuthorization -> AwaitingAuthorization(
                    step = step,
                    onContinueOnGitHub = { onContinueOnGitHub(step.userCode, step.verificationUri) },
                    onCancel = onCancel,
                )
                is SignInStep.AwaitingBrowser -> AwaitingBrowser(forgeName, onOpenAgain = { onOpenUrl(step.authorizationUrl) }, onCancel = onCancel)
                SignInStep.ChooseMethod, is SignInStep.Failed -> ChooseMethod(
                    state = state,
                    error = (step as? SignInStep.Failed)?.error,
                    onHostChange = onHostChange,
                    onClientIdChange = onClientIdChange,
                    onSelectGitLabServer = onSelectGitLabServer,
                    onStartDeviceFlow = onStartDeviceFlow,
                    onStartBrowserSignIn = onStartBrowserSignIn,
                    onSubmitToken = onSubmitToken,
                    onSubmitPassword = onSubmitPassword,
                    onOpenUrl = onOpenUrl,
                    onDismissError = onDismissError,
                )
            }
        }
    }
}

/**
 * Every forge one can sign in to, each a row with its logo, its name and where it is. Seven forges don't fit a row of tabs
 * on a phone, and at larger text half of them went off its edge; a list wraps and scrolls like the rest of the app.
 */
@Composable
private fun ForgeList(onSelectForge: (SignInForge) -> Unit) {
    val colors = Soft.colors
    Column(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(vertical = 12.dp)) {
        for (forge in SignInForge.entries) {
            NavigationRow(stringResource(forge.label), onClick = { onSelectForge(forge) }, summary = stringResource(forge.where)) {
                ForgeIcon(forge.icon, size = 18.dp, tint = colors.ink, contentDescription = null)
            }
        }
    }
}

private val SignInForge.label: Int
    get() = when (this) {
        SignInForge.GITHUB -> R.string.sign_in_forge_github
        SignInForge.GITLAB -> R.string.sign_in_forge_gitlab
        SignInForge.CODEBERG -> R.string.sign_in_forge_codeberg
        SignInForge.GITEA -> R.string.sign_in_forge_gitea
        SignInForge.SOURCEHUT -> R.string.sign_in_forge_sourcehut
        SignInForge.TANGLED -> R.string.sign_in_forge_tangled
        SignInForge.OTHER -> R.string.sign_in_forge_other_server
    }

/** Where the forge is, or what it takes. */
private val SignInForge.where: Int
    get() = when (this) {
        SignInForge.GITHUB -> R.string.sign_in_where_github
        SignInForge.GITLAB -> R.string.sign_in_where_gitlab
        SignInForge.CODEBERG -> R.string.sign_in_where_codeberg
        SignInForge.GITEA -> R.string.sign_in_where_gitea
        SignInForge.SOURCEHUT -> R.string.sign_in_where_sourcehut
        SignInForge.TANGLED -> R.string.sign_in_where_tangled
        SignInForge.OTHER -> R.string.sign_in_where_other
    }

@Composable
private fun ChooseMethod(
    state: SignInUiState,
    error: SignInError?,
    onHostChange: (String) -> Unit,
    onClientIdChange: (String) -> Unit,
    onSelectGitLabServer: (Boolean) -> Unit,
    onStartDeviceFlow: () -> Unit,
    onStartBrowserSignIn: () -> Unit,
    onSubmitToken: (String) -> Unit,
    onSubmitPassword: (identifier: String, password: String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = Soft.colors
    var token by rememberSaveable { mutableStateOf("") }

    if (state.forge == SignInForge.GITLAB) {
        // GitLab is gitlab.com for most, and a server of their own for the others: the same choice, one step further.
        SoftSwitch(
            options = listOf(stringResource(R.string.sign_in_gitlab_com), stringResource(R.string.sign_in_gitlab_own)),
            selected = if (state.gitlabOwnServer) 1 else 0,
            onSelect = { onSelectGitLabServer(it == 1) },
        )
    }
    if (state.usesHost) {
        SoftTextField(
            value = state.host,
            onValueChange = {
                onHostChange(it)
                if (error != null) onDismissError()
            },
            label = stringResource(R.string.sign_in_host),
            placeholder = stringResource(if (state.forge == SignInForge.GITLAB) R.string.sign_in_host_example_gitlab else R.string.sign_in_host_example),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        // Once the server has said what it runs, the screen says it back, and asks for what that kind needs.
        val kind = when (state.otherType) {
            ForgeType.GITLAB -> stringResource(R.string.sign_in_kind_gitlab)
            ForgeType.FORGEJO -> stringResource(R.string.sign_in_kind_forgejo)
            ForgeType.GITEA -> stringResource(R.string.sign_in_kind_gitea)
            else -> null
        }
        Text(
            when {
                kind != null -> stringResource(R.string.sign_in_host_is, kind)
                state.forge == SignInForge.GITLAB -> stringResource(R.string.sign_in_host_hint_gitlab)
                else -> stringResource(R.string.sign_in_host_hint)
            },
            style = Soft.type.secondary,
            color = colors.inkMuted,
        )
        // Asked of a GitLab: one's own as soon as it is chosen, a server typed elsewhere once it says it is one.
        if (state.otherType == ForgeType.GITLAB || (state.forge == SignInForge.GITLAB && state.otherType == null)) {
            SoftTextField(
                value = state.oauthClientId,
                onValueChange = onClientIdChange,
                label = stringResource(R.string.sign_in_client_id),
                placeholder = "",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.sign_in_client_id_hint), style = Soft.type.secondary, color = colors.inkMuted)
        }
    }
    if (error != null) {
        // A refused app password isn't a token.
        val message = if (state.passwordSignInAvailable && error == SignInError.INVALID_TOKEN) R.string.sign_in_error_invalid_password else error.message
        Text(stringResource(message, state.forgeName.ifBlank { state.host }), color = colors.accent, style = Soft.type.body)
    }
    if (state.passwordSignInAvailable) {
        PasswordSignIn(state, onSubmitPassword, onOpenUrl, onEdit = { if (error != null) onDismissError() })
        return
    }
    val quickSignIn = state.deviceFlowAvailable || state.browserSignInAvailable
    if (quickSignIn) {
        SoftButton(
            stringResource(R.string.sign_in_with_forge, state.forgeName),
            if (state.deviceFlowAvailable) onStartDeviceFlow else onStartBrowserSignIn,
            Modifier.fillMaxWidth(),
        )
        // GitHub's own screen asks for "full control of private repositories" and to delete them: said why, before it does.
        if (state.forge == SignInForge.GITHUB) {
            Text(stringResource(R.string.sign_in_github_access), style = Soft.type.secondary, color = colors.inkMuted)
        }
        Text(
            stringResource(R.string.sign_in_or),
            style = Soft.type.label,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(stringResource(R.string.sign_in_token_title), style = Soft.type.section, color = colors.ink)
    Text(
        stringResource(
            when {
                state.forge == SignInForge.GITHUB -> R.string.sign_in_token_body
                state.forge == SignInForge.SOURCEHUT -> R.string.sign_in_token_body_sourcehut
                state.otherType?.speaksForgejo == true -> R.string.sign_in_token_body_forgejo
                state.forge == SignInForge.GITLAB || state.otherType == ForgeType.GITLAB -> R.string.sign_in_token_body_gitlab
                else -> R.string.sign_in_token_body_forgejo
            }
        ),
        style = Soft.type.body,
        color = colors.inkMuted,
    )
    state.personalAccessTokenUrl?.let { url ->
        Text(
            stringResource(R.string.sign_in_create_token, state.forgeName),
            style = Soft.type.label,
            color = colors.accent,
            modifier = Modifier
                .clip(SoftTokens.Pill)
                .clickable(role = Role.Button) { onOpenUrl(url) }
                .heightIn(min = 48.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }
    SoftTextField(
        value = token,
        onValueChange = {
            token = it
            if (error != null) onDismissError()
        },
        label = stringResource(R.string.sign_in_token_label),
        placeholder = "",
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmitToken(token) }),
        modifier = Modifier.fillMaxWidth(),
    )
    val submit = stringResource(R.string.sign_in_with_token)
    if (quickSignIn) {
        SoftTonalButton(submit, { onSubmitToken(token) }, Modifier.fillMaxWidth())
    } else {
        SoftButton(submit, { onSubmitToken(token) }, Modifier.fillMaxWidth())
    }
}

/** Tangled: an AT Protocol account, signed in with its handle and an app password made where the account lives. */
@Composable
private fun PasswordSignIn(
    state: SignInUiState,
    onSubmit: (identifier: String, password: String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val colors = Soft.colors
    var handle by rememberSaveable { mutableStateOf("") }
    // Not kept across process death: a password has no business in saved state.
    var password by remember { mutableStateOf("") }
    Text(stringResource(R.string.sign_in_password_title), style = Soft.type.section, color = colors.ink)
    Text(stringResource(R.string.sign_in_password_body_tangled), style = Soft.type.body, color = colors.inkMuted)
    state.personalAccessTokenUrl?.let { url ->
        Text(
            stringResource(R.string.sign_in_create_app_password),
            style = Soft.type.label,
            color = colors.accent,
            modifier = Modifier
                .clip(SoftTokens.Pill)
                .clickable(role = Role.Button) { onOpenUrl(url) }
                .heightIn(min = 48.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }
    SoftTextField(
        value = handle,
        onValueChange = {
            handle = it
            onEdit()
        },
        label = stringResource(R.string.sign_in_handle_label),
        placeholder = stringResource(R.string.sign_in_handle_example),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    SoftTextField(
        value = password,
        onValueChange = {
            password = it
            onEdit()
        },
        label = stringResource(R.string.sign_in_app_password_label),
        placeholder = "",
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit(handle, password) }),
        modifier = Modifier.fillMaxWidth(),
    )
    SoftButton(stringResource(R.string.sign_in_with_forge, state.forgeName), { onSubmit(handle, password) }, Modifier.fillMaxWidth())
}

@Composable
private fun AwaitingAuthorization(
    step: SignInStep.AwaitingAuthorization,
    onContinueOnGitHub: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = Soft.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.sign_in_enter_code), style = Soft.type.body, color = colors.ink)
        Text(
            step.userCode,
            style = Soft.type.title.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
            color = colors.ink,
            modifier = Modifier.semantics { contentDescription = step.userCode.toList().joinToString(" ") },
        )
        SoftButton(stringResource(R.string.sign_in_copy_and_open), onContinueOnGitHub, Modifier.fillMaxWidth())
    }
    LinearProgressIndicator(Modifier.fillMaxWidth().clip(SoftTokens.Pill), color = colors.accent, trackColor = colors.surface)
    Text(stringResource(R.string.sign_in_waiting), style = Soft.type.body, color = colors.inkMuted)
    SoftTonalButton(stringResource(R.string.cancel), onCancel)
}

/** The forge's approval page is open in the browser; it comes back here on its own. */
@Composable
private fun AwaitingBrowser(forgeName: String, onOpenAgain: () -> Unit, onCancel: () -> Unit) {
    val colors = Soft.colors
    Text(stringResource(R.string.sign_in_browser_waiting, forgeName), style = Soft.type.body, color = colors.ink)
    LinearProgressIndicator(Modifier.fillMaxWidth().clip(SoftTokens.Pill), color = colors.accent, trackColor = colors.surface)
    SoftButton(stringResource(R.string.sign_in_browser_open_again, forgeName), onOpenAgain, Modifier.fillMaxWidth())
    SoftTonalButton(stringResource(R.string.cancel), onCancel)
}

@Composable
private fun Verifying(forgeName: String) {
    val colors = Soft.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = colors.accent, trackColor = colors.surface)
        Text(stringResource(R.string.sign_in_verifying, forgeName), style = Soft.type.body, color = colors.ink)
    }
}

private val SignInError.message: Int
    get() = when (this) {
        SignInError.NETWORK -> R.string.sign_in_error_network
        SignInError.INVALID_TOKEN -> R.string.sign_in_error_invalid_token
        SignInError.RATE_LIMITED -> R.string.sign_in_error_rate_limited
        SignInError.DENIED -> R.string.sign_in_error_denied
        SignInError.EXPIRED -> R.string.sign_in_error_expired
        SignInError.NOT_A_FORGE -> R.string.sign_in_error_not_a_forge
        SignInError.UNKNOWN -> R.string.sign_in_error_unknown
    }
