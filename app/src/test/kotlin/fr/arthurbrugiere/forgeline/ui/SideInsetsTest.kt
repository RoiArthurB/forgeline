package fr.arthurbrugiere.forgeline.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.you.YouScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In landscape the navigation bar (or a camera cutout) sits on a side: content must keep clear of it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h360dp-land-xhdpi")
class SideInsetsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun the_you_tab_keeps_clear_of_a_side_navigation_bar() {
        val activity = composeRule.activity
        composeRule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(activity.window, false) }
        composeRule.setContent { ForgelineTheme(darkTheme = false) { YouScreen(SessionState.SignedOut, onSignIn = {}, onOpenSettings = {}) } }
        val left = 48 // dp: a three-button navigation bar on the left edge, as on a phone turned clockwise
        composeRule.runOnUiThread {
            val px = (left * activity.resources.displayMetrics.density).toInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(px, 0, 0, 0))
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(px, 0, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        }
        composeRule.waitForIdle()

        val sign = composeRule.onNodeWithText("Sign in").getUnclippedBoundsInRoot()
        assertWithMessage("Sign in starts at ${sign.left}").that(sign.left.value).isAtLeast(left.toFloat())
    }
}
