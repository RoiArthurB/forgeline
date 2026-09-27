package fr.arthurbrugiere.forgeline.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.ui.format.parseHexColor
import fr.arthurbrugiere.forgeline.repo.Loadable
import java.time.Instant

fun <T> LazyListScope.loadable(
    loadable: Loadable<List<T>>,
    emptyMessage: Int,
    onRetry: () -> Unit,
    content: LazyListScope.(List<T>) -> Unit,
) {
    when (loadable) {
        Loadable.Idle, Loadable.Loading -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        is Loadable.Failed -> item(key = "failed") {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.repo_tab_failed), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(loadable.error.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
        is Loadable.Loaded -> if (loadable.value.isEmpty()) item(key = "empty") { Message(stringResource(emptyMessage)) } else content(loadable.value)
    }
}

@Composable
fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun LabelChip(label: Label) {
    val color = parseHexColor(label.color?.let { "#$it" }) ?: MaterialTheme.colorScheme.secondaryContainer
    Text(
        label.name,
        style = MaterialTheme.typography.labelSmall,
        color = if (color.luminance() > 0.5f) Color.Black else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}

fun relative(instant: Instant, nowMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(instant.toEpochMilli(), nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()

val ForgeError.message: Int
    get() = when (this) {
        ForgeError.Network -> R.string.trending_error_offline
        is ForgeError.RateLimited -> R.string.trending_error_rate_limited
        else -> R.string.sign_in_error_unknown
    }
