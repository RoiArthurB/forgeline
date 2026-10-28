package fr.arthurbrugiere.forgeline.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftCheck
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField

/** Puts one repository in or out of each collection, or in a new one named here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionPickerSheet(
    repoName: String,
    state: CollectionPickerUiState,
    onToggle: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Soft.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.collection_pick_title, repoName), style = Soft.type.name, color = colors.ink, modifier = Modifier.semantics { heading() })
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                state.collections.forEach { collection ->
                    val held = collection.id in state.holding
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = held, role = Role.Checkbox, onValueChange = { onToggle(collection.id) })
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SoftCheck(held)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(collection.name, style = Soft.type.body, color = colors.ink)
                            Text(pluralStringResource(R.plurals.collections_count, collection.repoCount, collection.repoCount), style = Soft.type.meta, color = colors.inkMuted)
                        }
                    }
                }
            }
            var name by rememberSaveable { mutableStateOf("") }
            val create = {
                onCreate(name)
                name = ""
            }
            SoftTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.collections_new),
                background = colors.surface,
                trailing = if (name.isBlank()) {
                    null
                } else {
                    {
                        SoftIconButton(onClick = create) {
                            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.collections_create), tint = colors.ink)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) create() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
