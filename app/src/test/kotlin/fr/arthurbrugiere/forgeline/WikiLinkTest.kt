package fr.arthurbrugiere.forgeline

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A link to a wiki page, from the browser or another app, opens that page here. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class WikiLinkTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Test
    fun a_link_to_a_wiki_page_opens_it_in_the_app() {
        // Regression: it opened the repository's page.
        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/paperclipai/paperclip/wiki/Install".toUri())
            .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntil(FLOW_WAIT_MILLIS) {
                composeRule.onAllNodes(hasText("Run the installer, then sign in.")).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
