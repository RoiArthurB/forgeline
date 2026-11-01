package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftColors
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDark
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLight

/** Whether code is shown dark, light, or (null) in the app's own theme: the Code theme setting. */
val LocalCodeDarkTheme = staticCompositionLocalOf<Boolean?> { null }

/**
 * The palette code is shown in: the app's own, or the other theme's when the Code theme setting asks for it. Its ground
 * and surface go under the code too, since dark code tokens can't be read on a light page, nor light ones on a dark.
 */
internal fun codeColors(app: SoftColors, dark: Boolean?): SoftColors = when (dark) {
    null, app.isDark -> app
    true -> SoftDark
    false -> SoftLight
}

/** The palette code is shown in here; see [codeColors]. */
val currentCodeColors: SoftColors
    @Composable @ReadOnlyComposable
    get() = codeColors(Soft.colors, LocalCodeDarkTheme.current)
