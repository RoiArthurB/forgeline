package fr.arthurbrugiere.forgeline.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice

/**
 * Whether a page that didn't load wants the reader signed in: the forge turned down someone signed out (SourceHut
 * shows nothing to anyone else). Signed in, the same answer means the sign-in ended, which the error itself says.
 */
fun ForgeError?.asksToSignIn(signedIn: Boolean): Boolean = this == ForgeError.Unauthorized && !signedIn

/**
 * What a page of [forge] says when it shows nothing signed out: a way to sign in. Regression: SourceHut's links said
 * the forge no longer accepted a sign-in that was never there.
 */
@Composable
fun SignInToRead(forge: ForgeInstance, onSignIn: () -> Unit) = SoftNotice(
    stringResource(R.string.sign_in_to_read_title, forge.displayName),
    stringResource(R.string.sign_in_to_read_body, forge.displayName),
    action = stringResource(R.string.sign_in),
    onAction = onSignIn,
)

/**
 * What a search says of a forge that turned it away for want of an account there: Codeberg answers no repository
 * search from someone signed out (2026-11-12). Regression: its results went missing without a word.
 */
@Composable
fun SignInToSearch(forge: ForgeInstance, onSignIn: () -> Unit) = SoftNotice(
    stringResource(R.string.search_sign_in_title, forge.displayName),
    stringResource(R.string.search_sign_in_body, forge.displayName),
    action = stringResource(R.string.sign_in),
    onAction = onSignIn,
)
