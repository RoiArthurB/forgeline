package fr.arthurbrugiere.forgeline.ui

import androidx.compose.runtime.compositionLocalOf
import fr.arthurbrugiere.forgeline.core.model.RepoId

/** Opens a release's page from anywhere a link or a row names one; null outside the app shell, where it opens its repository. */
val LocalOpenRelease = compositionLocalOf<((RepoId, String) -> Unit)?> { null }

/** Opens a discussion's page from anywhere a link or a row names one; null outside the app shell, where it opens on its forge. */
val LocalOpenDiscussion = compositionLocalOf<((RepoId, Int) -> Unit)?> { null }
