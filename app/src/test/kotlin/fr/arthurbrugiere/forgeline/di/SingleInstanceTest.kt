package fr.arthurbrugiere.forgeline.di

import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewRepository
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Regression: repositories that hold a lock or remember what they asked were built anew for every class that used
 * them, so the lock was never shared. Two token refreshes could run at once and spend the same refresh token.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class SingleInstanceTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var accountsAgain: AccountRepository
    @Inject lateinit var previews: FeedPreviewRepository
    @Inject lateinit var previewsAgain: FeedPreviewRepository
    @Inject lateinit var trending: TrendingRepository
    @Inject lateinit var trendingAgain: TrendingRepository
    @Inject lateinit var repos: fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
    @Inject lateinit var reposAgain: fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository

    @Before
    fun inject() = hiltRule.inject()

    @Test
    fun the_account_repository_and_its_refresh_lock_are_shared() {
        assertThat(accountsAgain).isSameInstanceAs(accounts)
    }

    @Test
    fun the_feed_previews_remember_what_is_being_fetched_across_screens() {
        assertThat(previewsAgain).isSameInstanceAs(previews)
    }

    @Test
    fun the_releases_a_repository_listed_are_there_for_the_release_page() {
        // Regression: each screen had its own repository, so a release opened from its list was loaded again, and
        // lost what only the list knows of it (whether it is the latest).
        assertThat(reposAgain).isSameInstanceAs(repos)
    }

    @Test
    fun trending_refreshes_share_one_lock() {
        assertThat(trendingAgain).isSameInstanceAs(trending)
    }
}
