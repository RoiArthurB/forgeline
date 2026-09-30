package fr.arthurbrugiere.forgeline.core.ui.format

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.ui.R

// A forge is shown by its logo (Simple Icons, CC0) wherever a list mixes forges. A self-hosted server also gets its
// host, since the Forgejo logo alone doesn't say which server. Screen readers still hear the forge's name.

@get:DrawableRes
val ForgeInstance.iconRes: Int
    get() = when {
        this == ForgeInstance.Codeberg -> R.drawable.ic_forge_codeberg
        type == ForgeType.GITHUB -> R.drawable.ic_forge_github
        else -> R.drawable.ic_forge_forgejo
    }

/** What to write next to the logo: a self-hosted server's host, nothing for forges the logo names alone. */
val ForgeInstance.hostLabel: String?
    get() = if (this == ForgeInstance.GitHub || this == ForgeInstance.Codeberg) null else host

@Composable
fun ForgeIcon(forge: ForgeInstance, modifier: Modifier = Modifier, size: Dp = 14.dp, tint: Color = LocalContentColor.current) {
    Icon(painterResource(forge.iconRes), contentDescription = forge.displayName, tint = tint, modifier = modifier.size(size))
}

/** The logo, then a self-hosted server's host in [style]. */
@Composable
fun ForgeMark(forge: ForgeInstance, style: TextStyle, color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        ForgeIcon(forge, size = size, tint = color)
        forge.hostLabel?.let { Text(it, style = style, color = color, maxLines = 1) }
    }
}

private fun ForgeInstance.inlineId() = "forge:$host"

/**
 * Puts [forge]'s logo inside a line of text, for lines built as one [AnnotatedString]. The text then needs
 * [forgeInlineContent] for the same forge. Its alternate text is the forge's name, for screen readers.
 */
fun AnnotatedString.Builder.appendForge(forge: ForgeInstance) {
    appendInlineContent(forge.inlineId(), forge.displayName)
    forge.hostLabel?.let { append(" $it") }
}

/** What draws the logo [appendForge] put in the text, as tall as the text. */
fun forgeInlineContent(forge: ForgeInstance, tint: Color): Map<String, InlineTextContent> = mapOf(
    forge.inlineId() to InlineTextContent(Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
        Icon(painterResource(forge.iconRes), contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
    },
)
