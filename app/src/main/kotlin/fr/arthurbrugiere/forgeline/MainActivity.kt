package fr.arthurbrugiere.forgeline

import fr.arthurbrugiere.forgeline.ui.LocalUserSettings
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavKey
import fr.arthurbrugiere.forgeline.navigation.linkRoute
import fr.arthurbrugiere.forgeline.navigation.ForgeLinks
import android.widget.Toast
import fr.arthurbrugiere.forgeline.notifications.EXTRA_LAST_READ_AT
import fr.arthurbrugiere.forgeline.notifications.EXTRA_UNREAD
import fr.arthurbrugiere.forgeline.ui.openInCustomTab
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.core.ui.theme.isDark
import fr.arthurbrugiere.forgeline.session.SessionViewModel
import fr.arthurbrugiere.forgeline.ui.ForgelineApp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val sessionViewModel: SessionViewModel by viewModels()
    private var link by mutableStateOf<NavKey?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { viewModel.uiState.value is MainUiState.Loading }
        // A restored activity already shows the link it was opened with.
        if (savedInstanceState == null) openLink(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val settings = (uiState as? MainUiState.Ready)?.settings ?: UserSettings()
            val darkTheme = settings.themeMode.isDark(isSystemInDarkTheme())

            DisposableEffect(darkTheme) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }

            ForgelineTheme(
                darkTheme = darkTheme,
                amoledBlack = settings.amoledBlack,
            ) {
                val session by sessionViewModel.session.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalUserSettings provides settings) {
                    ForgelineApp(
                        session = session,
                        onSignOut = sessionViewModel::signOut,
                        link = link,
                        onLinkOpened = { link = null },
                        // Not known until the settings are read: the app waits rather than open on the wrong tab.
                        startTab = (uiState as? MainUiState.Ready)?.settings?.startTab,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) return openShared(intent.getStringExtra(Intent.EXTRA_TEXT))
        val url = intent.dataString ?: return
        val lastReadAt = intent.getLongExtra(EXTRA_LAST_READ_AT, -1).takeIf { it >= 0 }
        val route = linkRoute(url, intent.getBooleanExtra(EXTRA_UNREAD, false), lastReadAt)
        // A github.com page Forgeline can't show (settings, orgs...): hand it to the browser.
        if (route == null) openInCustomTab(this, url) else link = route
    }

    /**
     * A page shared to Forgeline from another app opens here when it is on a forge Forgeline knows. Anything else is
     * said to be out of reach rather than sent back to the browser it most likely came from.
     */
    private fun openShared(text: String?) {
        val route = ForgeLinks.addressIn(text)?.let(::linkRoute)
        if (route == null) Toast.makeText(this, R.string.share_open_unknown, Toast.LENGTH_LONG).show() else link = route
    }
}
