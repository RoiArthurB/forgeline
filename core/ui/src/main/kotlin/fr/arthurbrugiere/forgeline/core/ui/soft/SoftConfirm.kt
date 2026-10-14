package fr.arthurbrugiere.forgeline.core.ui.soft

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The ink pill: an action that can't be taken back (deleting, unpublishing, merging against failed checks). Ember says
 * "this is the way forward"; ink says "this is final", and is never the pill a thumb lands on by habit. Not [enabled],
 * it goes quiet like the filled button.
 */
@Composable
fun SoftDangerButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = Soft.colors
    Box(
        modifier
            .clip(SoftTokens.Pill)
            .background(if (enabled) colors.ink else colors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Soft.type.control, color = if (enabled) colors.ground else colors.inkMuted)
    }
}

/** One choice among a few, in a sheet: a ring that fills in ember for the one picked, its name, and a line about it. */
@Composable
fun SoftChoiceRow(title: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier, summary: String? = null) {
    val colors = Soft.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(SoftTokens.RowCorner)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) colors.accent else colors.inkMuted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = Soft.type.control, color = colors.ink)
            summary?.let { Text(it, style = Soft.type.meta, color = colors.inkMuted) }
        }
    }
}

/**
 * Asks before something happens, from the foot of the screen where the thumb is: what it is, what it does, anything
 * to pick or write first ([content]), then a way out beside the way on. [danger] is for what can't be taken back: the
 * way on is then the ink pill. Nothing happens while [confirmEnabled] is false, and the way out always works.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SoftConfirmSheet(
    title: String,
    confirm: String,
    cancel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = Soft.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = Soft.type.name, color = colors.ink, modifier = Modifier.semantics { heading() })
            body?.let { Text(it, style = Soft.type.body, color = colors.inkMuted) }
            content?.invoke()
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SoftTonalButton(cancel, onDismiss)
                if (danger) SoftDangerButton(confirm, onConfirm, enabled = confirmEnabled) else SoftButton(confirm, onConfirm, enabled = confirmEnabled)
            }
        }
    }
}
