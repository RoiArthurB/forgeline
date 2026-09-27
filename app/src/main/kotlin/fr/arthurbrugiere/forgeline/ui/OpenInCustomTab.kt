package fr.arthurbrugiere.forgeline.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import fr.arthurbrugiere.forgeline.R

/** Opens forge pages we can't render natively (login, token creation) in a Custom Tab over the app. */
@Composable
fun rememberCustomTabOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { url -> openInCustomTab(context, url) } }
}

fun openInCustomTab(context: Context, url: String) {
    val intent = browserIntent(context, url)
    if (intent == null) {
        Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
    }
}

/**
 * A Custom Tab intent pinned to a browser. Forgeline handles github.com links itself, so an
 * unpinned intent could resolve back to Forgeline and bounce between the two forever.
 */
fun browserIntent(context: Context, url: String): Intent? {
    val packageManager = context.packageManager
    // Any https page resolves to browsers only, whatever the user chose for github.com.
    val probe = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    val browsers = packageManager.queryIntentActivities(probe, 0)
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName }
    val default = packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName
    val browser = default?.takeIf { it in browsers } ?: browsers.firstOrNull() ?: return null
    return CustomTabsIntent.Builder().setShowTitle(true).build().intent.apply {
        data = url.toUri()
        setPackage(browser)
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
