package fr.arthurbrugiere.forgeline.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * Says [message] in [snackbar] each time it becomes one, and tells through [onSaid] that it was taken, so it isn't
 * said again when the screen comes back.
 *
 * Regression: an effect keyed on the message told it was taken first, which cleared the message, which ended the
 * effect: the words went away as they appeared.
 */
@Composable
fun SayOnce(message: String?, snackbar: SnackbarHostState, onSaid: () -> Unit) {
    val current = rememberUpdatedState(message)
    val said = rememberUpdatedState(onSaid)
    LaunchedEffect(snackbar) {
        snapshotFlow { current.value }.filterNotNull().collect { words ->
            said.value()
            snackbar.showSnackbar(words)
        }
    }
}
