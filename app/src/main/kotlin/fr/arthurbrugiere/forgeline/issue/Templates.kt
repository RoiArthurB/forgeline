package fr.arthurbrugiere.forgeline.issue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ContactLink
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHairline
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens

/**
 * What the repository offers to start an issue or a pull request from: each its name and what it is for. One picked
 * fills the form, to be written on from there; the one it was filled from, [selected], is ticked. [hint] goes under.
 */
@Composable
internal fun Templates(
    templates: List<IssueTemplate>,
    picking: String?,
    error: ForgeError?,
    onPick: (IssueTemplate) -> Unit,
    selected: String? = null,
    hint: String? = null,
) {
    val colors = Soft.colors
    Column {
        Text(stringResource(R.string.new_issue_templates), style = Soft.type.name, color = colors.ink, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
        templates.forEachIndexed { index, template ->
            if (index > 0) SoftHairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(SoftTokens.RowCorner)
                    .clickable(enabled = picking == null, role = Role.Button) { onPick(template) }
                    .semantics { this.selected = template.key == selected }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(template.name, style = Soft.type.control, color = colors.ink)
                    template.about?.let { Text(it, style = Soft.type.meta, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.width(8.dp))
                when {
                    picking == template.key -> CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, trackColor = colors.ground, strokeWidth = 2.dp)
                    template.key == selected -> Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    else -> Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (error != null) Text(stringResource(R.string.new_issue_template_failed), style = Soft.type.secondary, color = colors.accent, modifier = Modifier.padding(top = 8.dp))
        hint?.let { Text(it, style = Soft.type.secondary, color = colors.inkMuted, modifier = Modifier.padding(top = 12.dp)) }
    }
}

/**
 * Where the repository sends people instead of its issues (a forum, a chat room, its documentation), each its name and
 * what it is for: picked, it opens out of the app.
 */
@Composable
internal fun ContactLinks(links: List<ContactLink>, onOpen: (String) -> Unit) {
    val colors = Soft.colors
    Column {
        Text(stringResource(R.string.new_issue_links), style = Soft.type.name, color = colors.ink, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
        links.forEachIndexed { index, link ->
            if (index > 0) SoftHairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(SoftTokens.RowCorner)
                    .clickable(role = Role.Button) { onOpen(link.url) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(link.name, style = Soft.type.control, color = colors.ink)
                    link.about?.let { Text(it, style = Soft.type.meta, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}
