package fr.arthurbrugiere.forgeline.ui

import androidx.compose.runtime.staticCompositionLocalOf
import fr.arthurbrugiere.forgeline.core.model.UserSettings

/**
 * What was chosen in Settings, for the screens whose gestures and marks follow it. Outside the app (a screen shown
 * alone in a test or a preview) it is every default, which is how Forgeline behaves untouched.
 */
val LocalUserSettings = staticCompositionLocalOf { UserSettings() }
