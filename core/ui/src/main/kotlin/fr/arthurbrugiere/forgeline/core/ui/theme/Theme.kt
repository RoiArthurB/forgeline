package fr.arthurbrugiere.forgeline.core.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import fr.arthurbrugiere.forgeline.core.model.ThemeMode

fun ThemeMode.isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

fun forgelineColorScheme(
    context: Context,
    darkTheme: Boolean,
    dynamicColor: Boolean,
    amoledBlack: Boolean,
): ColorScheme {
    val base = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> EmberDarkColors
        else -> EmberLightColors
    }
    return if (darkTheme && amoledBlack) base.toAmoled() else base
}

private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
)

@Composable
fun ForgelineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = remember(context, darkTheme, dynamicColor, amoledBlack) {
        forgelineColorScheme(context, darkTheme, dynamicColor, amoledBlack)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
