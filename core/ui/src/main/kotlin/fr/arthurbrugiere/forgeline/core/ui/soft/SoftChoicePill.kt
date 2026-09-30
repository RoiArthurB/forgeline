package fr.arthurbrugiere.forgeline.core.ui.soft

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A secondary choice (which forge, which account) folded into one pill that opens a short menu. It replaces a second
 * strip of chips under a screen's main switch, so a header carries one axis in the open and the other one tap away.
 * [name] says what is being chosen ("Forge", "Account") to screen readers; the pill reads "Forge, Codeberg".
 */
@Composable
fun SoftChoicePill(
    name: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn before an option's label, in its color (a forge's logo). */
    leading: (@Composable (index: Int, color: Color) -> Unit)? = null,
    /** The pill's fill: the track by default, so it reads on a header's tinted field and on the ground alike. */
    background: Color = Soft.colors.track,
    /** A shorter label for the pill itself (the menu and screen readers keep the full option). */
    shortLabel: (Int) -> String = { options[it] },
    maxWidth: Dp = 220.dp,
) {
    val colors = Soft.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val current = options.getOrElse(selected) { options.first() }
    Box(modifier) {
        Row(
            Modifier
                .clip(SoftTokens.Pill)
                .background(background)
                .clickable(role = Role.Button, onClickLabel = name) { open = true }
                .clearAndSetSemantics {
                    contentDescription = name
                    stateDescription = current
                }
                .heightIn(min = 48.dp)
                .widthIn(max = maxWidth)
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading?.invoke(selected, colors.ink)
            Text(shortLabel(selected.coerceIn(options.indices)), style = Soft.type.control, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(SoftTokens.FieldCorner),
            containerColor = colors.raised,
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selected
                DropdownMenuItem(
                    text = { Text(option, style = Soft.type.control, color = colors.ink) },
                    leadingIcon = leading?.let { { it(index, colors.ink) } },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.accent) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSelect(index)
                    },
                    modifier = Modifier.semantics { this.selected = isSelected },
                )
            }
        }
    }
}
