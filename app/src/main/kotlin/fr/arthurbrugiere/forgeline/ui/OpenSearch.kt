package fr.arthurbrugiere.forgeline.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Opens search; provided by the app shell so every top-level screen offers it without extra plumbing. */
val LocalOpenSearch = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Opens the reader's collections; provided by the app shell, offered from the You tab. */
val LocalOpenCollections = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Opens the first issues found in one's stars and in Trending; provided by the app shell, offered from Trending. */
val LocalOpenFirstIssues = staticCompositionLocalOf<(() -> Unit)?> { null }
