package fr.arthurbrugiere.forgeline

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** From a run to the report one of its artifacts holds, which GitHub's API can't give as the run's summary. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class ArtifactFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var accounts: AccountRepository

    /** The accounts are kept on disk, which the other app tests share: they expect nobody signed in. */
    @After
    fun signOut() = runBlocking { accounts.accounts.first().forEach { accounts.signOut(it.id) } }

    private fun waitFor(text: String) =
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun a_run_s_report_artifact_reads_in_the_app() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        val link = Intent(Intent.ACTION_VIEW, "https://github.com/paperclipai/paperclip/actions/runs/77".toUri())
            .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(link).use {
            waitFor("1 artifact")
            composeRule.onNodeWithText("report").performClick()
            waitFor("Serial wins.")
        }
    }
}
