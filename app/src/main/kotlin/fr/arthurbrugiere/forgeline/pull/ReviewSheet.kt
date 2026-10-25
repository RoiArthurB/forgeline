package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.DiffLine
import fr.arthurbrugiere.forgeline.core.model.DiffLineKind
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoiceRow
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
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
            Column(Modifier.selectableGroup()) {
                ReviewVerdict.entries.filter { it in verdicts }.forEach { option ->
                    SoftChoiceRow(stringResource(option.label), verdict == option, { verdict = option })
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
fun LineRemarkSheet(target: LineTarget, onAdd: (String, DiffLine?) -> Unit, onDismiss: () -> Unit) {
    val colors = Soft.colors
    var body by rememberSaveable(target) { mutableStateOf("") }
    // How many of the lines above it the remark reaches up over.
    var reach by rememberSaveable(target) { mutableIntStateOf(0) }
    val line = target.line
    val start = target.above.getOrNull(reach - 1)
    // The lines it covers, top to bottom.
    val covered = target.above.take(reach).reversed() + listOfNotNull(line)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val file = target.path.substringAfterLast('/')
            Text(
                if (line == null) stringResource(R.string.changes_remark_file_title, file) else stringResource(R.string.changes_remark_title, file, line.number),
                style = Soft.type.name,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            if (covered.isNotEmpty()) {
                Text(
                    covered.joinToString("\n") { it.text.trimEnd() }.trimIndent(),
                    style = Soft.type.meta.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = colors.inkMuted,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().clip(SoftTokens.RowCorner).background(colors.surface).padding(12.dp),
                )
            }
            if (line != null && target.above.isNotEmpty()) {
                if (start != null) Text(stringResource(R.string.changes_remark_lines, start.number, line.number), style = Soft.type.meta, color = colors.inkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftTonalButton(stringResource(R.string.changes_remark_more), onClick = { reach++ }, enabled = reach < target.above.size)
                    if (reach > 0) SoftTonalButton(stringResource(R.string.changes_remark_fewer), onClick = { reach-- })
                }
            }
            SoftTextField(
                value = body, onValueChange = { body = it },
                placeholder = stringResource(if (line == null) R.string.changes_remark_file_body else R.string.changes_remark_body),
                singleLine = false, minLines = 3, maxLines = 8,
            )
            // The lines as they are, to be rewritten into what they should say: a reviewer's suggestion, committed from the
            // thread. Only on lines the change keeps; one it takes away has nothing to replace.
            if (line != null && covered.none { it.kind == DiffLineKind.REMOVED }) {
                SoftTonalButton(stringResource(R.string.changes_remark_suggest), onClick = { body = withSuggestion(body, covered.joinToString("\n") { it.text }) })
            }
            SoftButton(stringResource(R.string.changes_remark_add), onClick = { onAdd(body, start) }, enabled = body.isNotBlank(), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** [written] with a suggestion block holding [line] after it, on a line of its own. */
internal fun withSuggestion(written: String, line: String): String {
    val before = if (written.isEmpty() || written.endsWith("\n")) written else "$written\n"
    return "$before```suggestion\n$line\n```\n"
}

/** The line's number as a reader knows it: in the file after the change, or before it for a line the change removes. */
internal val DiffLine.number: Int get() = newNumber ?: oldNumber ?: 0
