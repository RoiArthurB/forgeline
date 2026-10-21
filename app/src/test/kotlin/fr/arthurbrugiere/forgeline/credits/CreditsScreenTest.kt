package fr.arthurbrugiere.forgeline.credits

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withJson
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class CreditsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val libraries = Libs.Builder().withJson(
        """
        {
          "libraries": [
            {"uniqueId": "com.example:sample", "artifactVersion": "1.0.0", "name": "Sample Library",
             "developers": [{"name": "Jane Doe"}], "licenses": ["Apache-2.0"]}
          ],
          "licenses": {
            "Apache-2.0": {"name": "Apache License 2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0", "hash": "Apache-2.0"}
          }
        }
        """.trimIndent().toByteArray(),
    ).build()

    @Test
    fun states_the_app_license_and_lists_libraries() {
        composeRule.setContent { CreditsScreen(libraries = libraries, onOpenLicense = {}, onBack = {}) }

        composeRule.onNodeWithText("GNU General Public License v3.0", substring = true).assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Sample Library"))
        composeRule.onNodeWithText("Sample Library").assertIsDisplayed()
        // The forge logos come from Simple Icons.
        composeRule.onNodeWithText("Simple Icons", substring = true).assertExists()
    }

    @Test
    fun opens_the_app_license() {
        var opened = false
        composeRule.setContent { CreditsScreen(libraries = libraries, onOpenLicense = { opened = true }, onBack = {}) }

        composeRule.onNodeWithText("Read the license").performClick()

        assertThat(opened).isTrue()
    }

    /** On a screen tall enough to hold it all: a target cut by the edge of a shorter one measures as less than it is. */
    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun every_target_is_48dp_at_least() {
        composeRule.setContent { CreditsScreen(libraries = libraries, onOpenLicense = {}, onBack = {}) }
        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
