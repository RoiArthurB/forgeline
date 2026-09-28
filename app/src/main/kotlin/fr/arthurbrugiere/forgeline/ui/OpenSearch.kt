package fr.arthurbrugiere.forgeline.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Opens search; provided by the app shell so every top-level screen offers it without extra plumbing. */
val LocalOpenSearch = staticCompositionLocalOf<(() -> Unit)?> { null }
