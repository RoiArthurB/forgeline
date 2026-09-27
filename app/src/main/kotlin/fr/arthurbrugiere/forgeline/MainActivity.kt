package fr.arthurbrugiere.forgeline

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import fr.arthurbrugiere.forgeline.navigation.ForgeLinks
import fr.arthurbrugiere.forgeline.ui.openInCustomTab
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.core.ui.theme.isDark
import fr.arthurbrugiere.forgeline.session.SessionViewModel
import fr.arthurbrugiere.forgeline.ui.ForgelineApp

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
                dynamicColor = settings.dynamicColor,
                amoledBlack = settings.amoledBlack,
            ) {
                val session by sessionViewModel.session.collectAsStateWithLifecycle()
                ForgelineApp(
                    session = session,
                    onSignOut = sessionViewModel::signOut,
                    link = link,
                    onLinkOpened = { link = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent) {
        val url = intent.dataString ?: return
        val route = ForgeLinks.routeFor(url)
        // A github.com page Forgeline can't show (settings, orgs...): hand it to the browser.
        if (route == null) openInCustomTab(this, url) else link = route
    }
}
