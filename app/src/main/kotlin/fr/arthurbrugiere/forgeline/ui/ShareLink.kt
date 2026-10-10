package fr.arthurbrugiere.forgeline.ui

import fr.arthurbrugiere.forgeline.core.model.ShareTap
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft

/**
 * Sends the address of what a screen shows somewhere else: a tap hands it to Android's share sheet, a long press
 * copies it. [title] names what the address leads to, for the apps that show one.
 */
@Composable
fun ShareLinkButton(url: String, title: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // Settings can swap the two: copying on a tap, sharing on a long press.
    val copies = LocalUserSettings.current.shareTap == ShareTap.COPY
    val share = { shareLink(context, url, title) }
    val copy = { copyLink(context, url) }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = stringResource(if (copies) R.string.link_share else R.string.link_copy),
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (copies) share() else copy()
                },
                onClick = { if (copies) copy() else share() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Share, contentDescription = stringResource(if (copies) R.string.link_copy else R.string.link_share), tint = Soft.colors.ink)
    }
}

/** Offers [url] to the other apps, under [title] where they show one. */
fun shareLink(context: Context, url: String, title: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        title?.takeIf { it.isNotBlank() }?.let {
            putExtra(Intent.EXTRA_SUBJECT, it)
            // What the share sheet shows above the address.
            putExtra(Intent.EXTRA_TITLE, it)
        }
    }
    val chooser = Intent.createChooser(send, null).apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        copyLink(context, url)
    }
}

/** Puts [url] on the clipboard, and says so where Android doesn't. */
fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.link_clip_label), url))
    // Android 13 and later show what was copied themselves.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
}
