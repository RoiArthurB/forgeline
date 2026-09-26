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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sign_in_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.sign_in_headline), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.sign_in_privacy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    var token by rememberSaveable { mutableStateOf("") }

    if (error != null) {
        Text(
            stringResource(error.message),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (state.deviceFlowAvailable) {
        Button(onClick = onStartDeviceFlow, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sign_in_with_github))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(Modifier.weight(1f))
            Text(stringResource(R.string.sign_in_or), style = MaterialTheme.typography.labelLarge)
            HorizontalDivider(Modifier.weight(1f))
        }
    }
    Text(stringResource(R.string.sign_in_token_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.sign_in_token_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { onOpenUrl(state.personalAccessTokenUrl) }) {
        Text(stringResource(R.string.sign_in_create_token))
    }
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
        modifier = Modifier.fillMaxWidth(),
    )
    val submit: @Composable () -> Unit = { Text(stringResource(R.string.sign_in_with_token)) }
    if (state.deviceFlowAvailable) {
        FilledTonalButton(onClick = { onSubmitToken(token) }, modifier = Modifier.fillMaxWidth(), content = { submit() })
    } else {
        Button(onClick = { onSubmitToken(token) }, modifier = Modifier.fillMaxWidth(), content = { submit() })
    }
}

@Composable
private fun AwaitingAuthorization(
    step: SignInStep.AwaitingAuthorization,
    onContinueOnGitHub: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.sign_in_enter_code), style = MaterialTheme.typography.bodyLarge)
            Text(
                step.userCode,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.semantics { contentDescription = step.userCode.toList().joinToString(" ") },
            )
            Button(onClick = onContinueOnGitHub, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sign_in_copy_and_open))
            }
        }
    }
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.sign_in_waiting),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun Verifying() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(stringResource(R.string.sign_in_verifying))
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
