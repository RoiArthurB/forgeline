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

// A forge is shown by its logo (Simple Icons, CC0) and its name wherever a list mixes forges: "GitHub", "Codeberg" or
// a self-hosted server's host. A 14dp monochrome mark alone leaves two same-named rows to be told apart by decoding a
// glyph. Where the name is written the logo is decoration, so screen readers hear it once.

@get:DrawableRes
val ForgeInstance.iconRes: Int
    get() = when {
        this == ForgeInstance.Codeberg -> R.drawable.ic_forge_codeberg
        type == ForgeType.GITHUB -> R.drawable.ic_forge_github
        else -> R.drawable.ic_forge_forgejo
    }

/**
 * The logo alone. Its [contentDescription] is the forge's name; pass null where the name is written next to it (a chip's
 * label, a row's meta line) so it isn't read twice.
 */
@Composable
fun ForgeIcon(
    forge: ForgeInstance,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = forge.displayName,
) {
    Icon(painterResource(forge.iconRes), contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

/** The logo, then the forge's name in [style]. */
@Composable
fun ForgeMark(forge: ForgeInstance, style: TextStyle, color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        ForgeIcon(forge, size = size, tint = color, contentDescription = null)
        Text(forge.displayName, style = style, color = color, maxLines = 1)
    }
}

private fun ForgeInstance.inlineId() = "forge:$host"

/**
 * Puts [forge]'s logo and then its name inside a line of text, for lines built as one [AnnotatedString]. The text then
 * needs [forgeInlineContent] for the same forge. The logo's alternate text is a word joiner, silent and unbreakable: the
 * name follows it in the text, so screen readers must not hear it twice, the placeholder needs a character to sit on,
 * and a wrapping line must never leave the logo at the end of one line and its name at the start of the next.
 */
fun AnnotatedString.Builder.appendForge(forge: ForgeInstance) {
    appendInlineContent(forge.inlineId(), "\u2060")
    append("\u00A0${forge.displayName}")
}

/** What draws the logo [appendForge] put in the text, as tall as the text. */
fun forgeInlineContent(forge: ForgeInstance, tint: Color): Map<String, InlineTextContent> = mapOf(
    forge.inlineId() to InlineTextContent(Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
        Icon(painterResource(forge.iconRes), contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
    },
)
