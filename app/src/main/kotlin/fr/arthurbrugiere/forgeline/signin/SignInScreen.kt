package fr.arthurbrugiere.forgeline.signin

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
        if (state.step == SignInStep.SignedIn) onSignedIn()
    }

    SignInScreen(
        state = state,
        onStartDeviceFlow = viewModel::startDeviceFlow,
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
) {
    val colors = Soft.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.ground)
            .verticalScroll(rememberScrollState())
            .padding(bottom = listBottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SoftHeader(
            tint = colors.fields[0],
            title = stringResource(R.string.sign_in_title),
            onBack = onBack,
            backDescription = stringResource(R.string.navigate_up),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sign_in_headline), style = Soft.type.title.copy(fontSize = 26.sp, lineHeight = 31.sp), color = colors.ink)
                Text(stringResource(R.string.sign_in_privacy), style = Soft.type.body, color = colors.inkMuted, modifier = Modifier.widthIn(max = SoftTokens.MaxMeasure))
            }
        }
        Column(
            Modifier.widthIn(max = SoftTokens.MaxMeasure).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val step = state.step) {
                SignInStep.Verifying, SignInStep.SignedIn -> Verifying()
                is SignInStep.AwaitingAuthorization -> AwaitingAuthorization(
                    step = step,
                    onContinueOnGitHub = { onContinueOnGitHub(step.userCode, step.verificationUri) },
                    onCancel = onCancel,
                )
                SignInStep.ChooseMethod, is SignInStep.Failed -> ChooseMethod(
                    state = state,
                    error = (step as? SignInStep.Failed)?.error,
                    onStartDeviceFlow = onStartDeviceFlow,
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
    onStartDeviceFlow: () -> Unit,
    onSubmitToken: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = Soft.colors
    var token by rememberSaveable { mutableStateOf("") }

    if (error != null) {
        Text(stringResource(error.message), color = colors.accent, style = Soft.type.body)
    }
    if (state.deviceFlowAvailable) {
        SoftButton(stringResource(R.string.sign_in_with_github), onStartDeviceFlow, Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.sign_in_or),
            style = Soft.type.label,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(stringResource(R.string.sign_in_token_title), style = Soft.type.control.copy(fontSize = 17.sp, lineHeight = 22.sp), color = colors.ink)
    Text(stringResource(R.string.sign_in_token_body), style = Soft.type.body, color = colors.inkMuted)
    Text(
        stringResource(R.string.sign_in_create_token),
        style = Soft.type.label,
        color = colors.accent,
        modifier = Modifier
            .clip(SoftTokens.Pill)
            .clickable(role = Role.Button) { onOpenUrl(state.personalAccessTokenUrl) }
            .heightIn(min = 48.dp)
            .wrapContentHeight(Alignment.CenterVertically),
    )
    OutlinedTextField(
        value = token,
        onValueChange = {
            token = it
            if (error != null) onDismissError()
        },
        label = { Text(stringResource(R.string.sign_in_token_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmitToken(token) }),
        isError = error == SignInError.INVALID_TOKEN,
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.inkMuted,
            focusedLabelColor = colors.accent,
            cursorColor = colors.accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    val submit = stringResource(R.string.sign_in_with_token)
    if (state.deviceFlowAvailable) {
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(colors.fields[1]).padding(20.dp),
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

@Composable
private fun Verifying() {
    val colors = Soft.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = colors.accent, trackColor = colors.surface)
        Text(stringResource(R.string.sign_in_verifying), style = Soft.type.body, color = colors.ink)
    }
}

private val SignInError.message: Int
    get() = when (this) {
        SignInError.NETWORK -> R.string.sign_in_error_network
        SignInError.INVALID_TOKEN -> R.string.sign_in_error_invalid_token
        SignInError.RATE_LIMITED -> R.string.sign_in_error_rate_limited
        SignInError.DENIED -> R.string.sign_in_error_denied
        SignInError.EXPIRED -> R.string.sign_in_error_expired
        SignInError.UNKNOWN -> R.string.sign_in_error_unknown
    }
