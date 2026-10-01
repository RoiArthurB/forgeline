package fr.arthurbrugiere.forgeline.di

import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Regression: once Codeberg's and gitlab.com's Trending were shown to everyone, every app-level test started
 * downloading the lists really published that day, since only GitHub's clients were fakes. What they showed then
 * depended on the network and on the daily job.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AppTestsStayOffTheNetworkTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var clients: ForgeClients

    @Before
    fun inject() = hiltRule.inject()

    @Test
    fun no_published_trending_list_is_fetched_in_app_tests() {
        assertThat(clients.trending(ForgeInstance.Codeberg)).isNull()
        assertThat(clients.trending(ForgeInstance.GitLab)).isNull()
    }
}
