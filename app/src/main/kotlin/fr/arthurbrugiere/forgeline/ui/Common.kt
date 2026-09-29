package fr.arthurbrugiere.forgeline.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.readableOn

fun <T> LazyListScope.loadable(
    loadable: Loadable<List<T>>,
    emptyMessage: Int,
    onRetry: () -> Unit,
    content: LazyListScope.(List<T>) -> Unit,
) {
    when (loadable) {
        Loadable.Idle, Loadable.Loading -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Soft.colors.accent, trackColor = Soft.colors.surface)
            }
        }
        is Loadable.Failed -> item(key = "failed") {
            SoftNotice(
                stringResource(R.string.repo_tab_failed),
                stringResource(loadable.error.message),
                action = stringResource(R.string.retry),
                onAction = onRetry,
            )
        }
        is Loadable.Loaded -> if (loadable.value.isEmpty()) item(key = "empty") { Message(stringResource(emptyMessage)) } else content(loadable.value)
    }
}

/** A small state tag (Draft, Open, Archived…) on a soft pill. */
@Composable
fun Badge(text: String) {
    SoftTag(text)
}

/** A forge label in its own color, as the forge shows it, on a pill; text contrast follows the color. */
@Composable
fun LabelChip(label: Label) {
    val color = parseHexColor(label.color?.let { "#$it" }) ?: Soft.colors.surface
    Text(
        label.name,
        style = Soft.type.label,
        color = readableOn(color),
        maxLines = 1,
        modifier = Modifier
            .clip(SoftTokens.Pill)
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A quiet line of text standing in for an empty list. */
@Composable
fun Message(text: String) {
    Text(
        text,
        style = Soft.type.body,
        color = Soft.colors.inkMuted,
        modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
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
