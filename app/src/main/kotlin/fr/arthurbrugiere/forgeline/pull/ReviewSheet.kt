package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.DiffLine
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.ui.message

/**
 * A review of a pull request: what it decides, what it says, and how many remarks on lines go with it. The forge's
 * own verdicts only ([verdicts]): GitLab's API can't ask for changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(
    verdicts: Set<ReviewVerdict>,
    remarks: Int,
    isSending: Boolean,
    error: ForgeError?,
    onSubmit: (ReviewVerdict, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Soft.colors
    var verdict by rememberSaveable { mutableStateOf(ReviewVerdict.COMMENT) }
    var body by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.review_title), style = Soft.type.name, color = colors.ink, modifier = Modifier.semantics { heading() })
            Column {
                ReviewVerdict.entries.filter { it in verdicts }.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(SoftTokens.RowCorner)
                            .selectable(selected = verdict == option, role = Role.RadioButton, onClick = { verdict = option })
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (verdict == option) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (verdict == option) colors.accent else colors.inkMuted,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(option.label), style = Soft.type.control, color = colors.ink)
                    }
                }
            }
            SoftTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = stringResource(R.string.review_body),
                singleLine = false,
                minLines = 3,
                maxLines = 8,
            )
            if (remarks > 0) {
                Text(pluralStringResource(R.plurals.review_remarks, remarks, remarks), style = Soft.type.secondary, color = colors.inkMuted)
            }
            if (error != null) {
                Text(
                    stringResource(R.string.review_failed, (error as? ForgeError.Http)?.message ?: stringResource(error.message)),
                    style = Soft.type.secondary,
                    color = colors.accent,
                )
            }
            // A comment with nothing said and nothing remarked says nothing: the forges refuse it.
            val sayable = verdict != ReviewVerdict.COMMENT || body.isNotBlank() || remarks > 0
            SoftButton(
                stringResource(if (isSending) R.string.review_sending else R.string.review_send),
                onClick = { onSubmit(verdict, body) },
                enabled = sayable && !isSending,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal val ReviewVerdict.label: Int
    get() = when (this) {
        ReviewVerdict.COMMENT -> R.string.review_comment
        ReviewVerdict.APPROVE -> R.string.review_approve
        ReviewVerdict.REQUEST_CHANGES -> R.string.review_request_changes
    }

/** What is said of one line of a change, kept until the review is sent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineRemarkSheet(target: LineTarget, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = Soft.colors
    var body by rememberSaveable(target) { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.changes_remark_title, target.path.substringAfterLast('/'), target.line.number),
                style = Soft.type.name,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                target.line.text.trim(),
                style = Soft.type.meta.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = colors.inkMuted,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().clip(SoftTokens.RowCorner).background(colors.surface).padding(12.dp),
            )
            SoftTextField(value = body, onValueChange = { body = it }, placeholder = stringResource(R.string.changes_remark_body), singleLine = false, minLines = 3, maxLines = 8)
            SoftButton(stringResource(R.string.changes_remark_add), onClick = { onAdd(body) }, enabled = body.isNotBlank(), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The line's number as a reader knows it: in the file after the change, or before it for a line the change removes. */
internal val DiffLine.number: Int get() = newNumber ?: oldNumber ?: 0
