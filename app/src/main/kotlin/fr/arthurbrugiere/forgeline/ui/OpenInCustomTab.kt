package fr.arthurbrugiere.forgeline.ui

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.net.toUri

/** Opens forge pages we can't render natively (login, token creation) in a Custom Tab over the app. */
@Composable
fun rememberCustomTabOpener(): (String) -> Unit {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    return remember(context, uriHandler) {
        { url -> openInCustomTab(context, url) { uriHandler.openUri(url) } }
    }
}

private fun openInCustomTab(context: Context, url: String, fallback: () -> Unit) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
    } catch (e: ActivityNotFoundException) {
        fallback()
    }
}
