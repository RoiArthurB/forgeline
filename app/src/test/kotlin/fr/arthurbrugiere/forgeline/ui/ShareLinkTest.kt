package fr.arthurbrugiere.forgeline.ui

import fr.arthurbrugiere.forgeline.core.model.ShareTap
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import androidx.compose.runtime.CompositionLocalProvider
import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** What was last handed to the share sheet: the address, and the title it went under. Null when nothing was shared. */
fun sharedLink(): Pair<String?, String?>? {
    val chooser = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity ?: return null
    if (chooser.action != Intent.ACTION_CHOOSER) return null
    @Suppress("DEPRECATION")
    val sent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return null
    return sent.getStringExtra(Intent.EXTRA_TEXT) to sent.getStringExtra(Intent.EXTRA_SUBJECT)
}

/** What a copied link left on the clipboard. */
fun copiedText(): String? =
    ApplicationProvider.getApplicationContext<Application>().getSystemService(ClipboardManager::class.java)
        .primaryClip?.getItemAt(0)?.text?.toString()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ShareLinkTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(title: String? = "octo/tools", settings: UserSettings = UserSettings()) = composeRule.setContent {
        CompositionLocalProvider(LocalUserSettings provides settings) {
            ForgelineTheme { ShareLinkButton("https://github.com/octo/tools", title) }
        }
    }

    @Test
    fun settings_can_swap_them_a_tap_copies_and_a_long_press_shares() {
        setContent(settings = UserSettings(shareTap = ShareTap.COPY))

        // The button then says what a tap does.
        composeRule.onNode(hasContentDescription("Copy link")).performClick()
        assertThat(copiedText()).isEqualTo("https://github.com/octo/tools")
        assertThat(sharedLink()).isNull()

        composeRule.onNode(hasContentDescription("Copy link")).performTouchInput { longClick() }
        assertThat(sharedLink()).isEqualTo("https://github.com/octo/tools" to "octo/tools")
    }

    @Test
    fun a_tap_hands_the_address_to_the_share_sheet_as_plain_text() {
        setContent()

        composeRule.onNode(hasContentDescription("Share link")).performClick()

        val chooser = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        @Suppress("DEPRECATION")
        val sent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(sent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(sent.type).isEqualTo("text/plain")
        assertThat(sent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("https://github.com/octo/tools")
        assertThat(sent.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("octo/tools")
        assertThat(sent.getStringExtra(Intent.EXTRA_TITLE)).isEqualTo("octo/tools")
    }

    @Test
    fun an_address_without_a_title_is_shared_alone() {
        setContent(title = " ")

        composeRule.onNode(hasContentDescription("Share link")).performClick()

        assertThat(sharedLink()).isEqualTo("https://github.com/octo/tools" to null)
    }

    @Test
    fun a_long_press_copies_the_address_without_sharing_it() {
        setContent()

        composeRule.onNode(hasContentDescription("Share link")).performTouchInput { longClick() }

        assertThat(copiedText()).isEqualTo("https://github.com/octo/tools")
        assertThat(sharedLink()).isNull()
    }

    @Test
    @Config(sdk = [32])
    fun a_copy_is_said_where_android_does_not_say_it() {
        setContent()

        composeRule.onNode(hasContentDescription("Share link")).performTouchInput { longClick() }

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Link copied")
    }

    @Test
    @Config(sdk = [33])
    fun a_copy_is_left_to_android_to_say_where_it_does() {
        setContent()

        composeRule.onNode(hasContentDescription("Share link")).performTouchInput { longClick() }

        assertThat(copiedText()).isEqualTo("https://github.com/octo/tools")
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    @Test
    fun it_is_large_enough_to_tap() {
        setContent()

        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
