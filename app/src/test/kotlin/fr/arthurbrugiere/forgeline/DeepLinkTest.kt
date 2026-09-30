package fr.arthurbrugiere.forgeline

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.ui.browserIntent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class DeepLinkTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val context = ApplicationProvider.getApplicationContext<HiltTestApplication>()
    private val packageManager = shadowOf(context.packageManager)

    @Before
    fun installBrowser() {
        // A browser that handles every https link, like any real phone has.
        val browser = ComponentName("org.example.browser", "org.example.browser.Main")
        packageManager.addActivityIfNotPresent(browser)
        packageManager.addIntentFilterForActivity(
            browser,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            },
        )
    }

    private fun handlers(url: String): List<String> =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE), 0)
            .map { it.activityInfo.packageName }

    @Test
    fun github_links_can_open_in_forgeline() {
        assertThat(handlers("https://github.com/acme/rocket/issues/42")).contains(context.packageName)
        assertThat(handlers("https://www.github.com/octocat")).contains(context.packageName)
    }

    @Test
    fun other_hosts_are_left_to_the_browser() {
        assertThat(handlers("https://gist.github.com/octocat/1")).doesNotContain(context.packageName)
        assertThat(handlers("https://example.com/")).doesNotContain(context.packageName)
    }

    @Test
    fun pages_forgeline_cannot_show_go_to_a_browser_never_back_to_forgeline() {
        val intent = browserIntent(context, "https://github.com/settings/tokens")

        assertThat(intent?.`package`).isEqualTo("org.example.browser")
        assertThat(intent?.dataString).isEqualTo("https://github.com/settings/tokens")
    }

    @Test
    fun an_incoming_link_to_such_a_page_is_handed_to_the_browser() {
        val link = Intent(Intent.ACTION_VIEW, "https://github.com/settings/tokens".toUri()).setClass(context, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(link).use { scenario ->
            scenario.onActivity { activity ->
                val started = shadowOf(activity).nextStartedActivity
                assertThat(started.dataString).isEqualTo("https://github.com/settings/tokens")
                assertThat(started.`package`).isEqualTo("org.example.browser")
            }
        }
    }

    @Test
    fun codeberg_links_can_open_in_forgeline() {
        assertThat(handlers("https://codeberg.org/forgejo/forgejo/issues/14601")).contains(context.packageName)
        assertThat(handlers("https://codeberg.org/alice")).contains(context.packageName)
    }
}
