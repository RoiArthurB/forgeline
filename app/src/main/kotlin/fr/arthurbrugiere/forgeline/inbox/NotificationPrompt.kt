package fr.arthurbrugiere.forgeline.inbox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import fr.arthurbrugiere.forgeline.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** How the Inbox asks for notifications: the system dialog first, then the app's settings once it was denied. */
enum class NotificationPrompt { ASK, OPEN_SETTINGS }

class NotificationPromptState(val prompt: NotificationPrompt?, val onAllow: () -> Unit)

@Composable
fun rememberNotificationPrompt(): NotificationPromptState {
    val context = LocalContext.current
    // Also false on Android 13+ until the runtime permission is granted.
    fun enabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    var isEnabled by remember { mutableStateOf(enabled()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    // The person may come back from system settings having changed their mind.
    LifecycleResumeEffect(Unit) {
        isEnabled = enabled()
        onPauseOrDispose {}
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        isEnabled = granted
        denied = !granted
    }
    val prompt = when {
        isEnabled -> null
        denied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> NotificationPrompt.OPEN_SETTINGS
        else -> NotificationPrompt.ASK
    }
    return NotificationPromptState(prompt) {
        if (prompt == NotificationPrompt.ASK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings(context)
        }
    }
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
}

@Composable
fun NotificationPromptCard(prompt: NotificationPrompt, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    // Unboxed like every other notice: a soft badge, the ask, one pill.
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).background(colors.fields[0], CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.inbox_permission_title), style = Soft.type.control.copy(fontSize = 17.sp, lineHeight = 22.sp), color = colors.ink)
                Text(stringResource(R.string.inbox_permission_body), style = Soft.type.secondary, color = colors.inkMuted)
            }
        }
        SoftButton(
            stringResource(if (prompt == NotificationPrompt.ASK) R.string.inbox_permission_allow else R.string.inbox_permission_settings),
            onClick = onAllow,
            modifier = Modifier.align(Alignment.End).padding(top = 12.dp),
        )
    }
}
