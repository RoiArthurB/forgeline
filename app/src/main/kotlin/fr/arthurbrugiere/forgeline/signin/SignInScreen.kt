package fr.arthurbrugiere.forgeline.signin

import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import androidx.compose.foundation.layout.imePadding
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeIcon
import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
        onHostChange = viewModel::setHost,
        onStartDeviceFlow = viewModel::startDeviceFlow,
        onStartBrowserSignIn = viewModel::startBrowserSignIn,
        onContinueOnGitHub = { code, uri ->
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("GitHub code", code))) }
            openUrl(uri)
        },
        onSubmitToken = viewModel::signInWithToken,
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
    onStartBrowserSignIn: () -> Unit = {},
) {
    val colors = Soft.colors
    val forgeName = state.forgeName
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
            tint = colors.fields[0],
            title = if (forgeName.isBlank()) stringResource(R.string.sign_in_headline_other) else stringResource(R.string.sign_in_headline, forgeName),
            onBack = onBack,
            backDescription = stringResource(R.string.navigate_up),
        ) {
            Text(
                stringResource(R.string.sign_in_privacy, forgeName.ifBlank { stringResource(R.string.sign_in_forge_other) }),
                style = Soft.type.body,
                color = colors.inkMuted,
                modifier = Modifier.widthIn(max = SoftTokens.MaxMeasure),
            )
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
                    onSelectForge = onSelectForge,
                    onHostChange = onHostChange,
                    onStartDeviceFlow = onStartDeviceFlow,
                    onStartBrowserSignIn = onStartBrowserSignIn,
                    onSubmitToken = onSubmitToken,
                    onOpenUrl = onOpenUrl,
                    onDismissError = onDismissError,
                )
            }
        }
    }
}

@Composable
private fun ChooseMethod(
    state: SignInUiState,
    error: SignInError?,
    onSelectForge: (SignInForge) -> Unit,
    onHostChange: (String) -> Unit,
    onStartDeviceFlow: () -> Unit,
    onStartBrowserSignIn: () -> Unit,
    onSubmitToken: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = Soft.colors
    var token by rememberSaveable { mutableStateOf("") }

    SoftSwitch(
        options = listOf(
            stringResource(R.string.sign_in_forge_github),
            stringResource(R.string.sign_in_forge_codeberg),
            stringResource(R.string.sign_in_forge_other),
        ),
        selected = state.forge.ordinal,
        onSelect = { onSelectForge(SignInForge.entries[it]) },
        // Named as well: this is where the forge is chosen.
        leading = { index, color -> ForgeIcon(SignInForge.entries[index].icon, size = 16.dp, tint = color) },
    )
    if (state.forge == SignInForge.OTHER) {
        SoftTextField(
            value = state.host,
            onValueChange = {
                onHostChange(it)
                if (error != null) onDismissError()
            },
            placeholder = stringResource(R.string.sign_in_host),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.sign_in_host_hint), style = Soft.type.secondary, color = colors.inkMuted)
    }
    if (error != null) {
        Text(stringResource(error.message, state.forgeName.ifBlank { state.host }), color = colors.accent, style = Soft.type.body)
    }
    val quickSignIn = state.deviceFlowAvailable || state.browserSignInAvailable
    if (quickSignIn) {
        SoftButton(
            stringResource(R.string.sign_in_with_forge, state.forgeName),
            if (state.deviceFlowAvailable) onStartDeviceFlow else onStartBrowserSignIn,
            Modifier.fillMaxWidth(),
        )
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
        stringResource(if (state.forge == SignInForge.GITHUB) R.string.sign_in_token_body else R.string.sign_in_token_body_forgejo),
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
        placeholder = stringResource(R.string.sign_in_token_label),
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
            style = Soft.type.title.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em),
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
