package fr.arthurbrugiere.forgeline.ui

import androidx.compose.runtime.compositionLocalOf
import fr.arthurbrugiere.forgeline.core.model.RepoId

/** Opens a release's page from anywhere a link or a row names one; null outside the app shell, where it opens its repository. */
val LocalOpenRelease = compositionLocalOf<((RepoId, String) -> Unit)?> { null }

/** Opens a discussion's page from anywhere a link or a row names one; null outside the app shell, where it opens on its forge. */
val LocalOpenDiscussion = compositionLocalOf<((RepoId, Int) -> Unit)?> { null }

/** Opens a page of a repository's wiki (its first when null); null outside the app shell, where the wiki opens on its forge. */
val LocalOpenWiki = compositionLocalOf<((RepoId, String?) -> Unit)?> { null }

/** Opens a topic's page from a repository's tags; null outside the app shell, where tags aren't tappable. */
val LocalOpenTopic = compositionLocalOf<((String) -> Unit)?> { null }

/** Opens one commit's changes from anywhere a link names it; null outside the app shell, where it opens on its forge. */
val LocalOpenCommit = compositionLocalOf<((RepoId, String) -> Unit)?> { null }
