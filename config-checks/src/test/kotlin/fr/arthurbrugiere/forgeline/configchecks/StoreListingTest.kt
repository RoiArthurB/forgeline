package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The store listing F-Droid reads from the repository, and Google Play takes through fastlane. Both cut or refuse a
 * text over their limits, and a language the app speaks should be one the listing is written in.
 */
class StoreListingTest {
    private val listings = File(Repo.root, "fastlane/metadata/android").listFiles { file -> file.isDirectory }.orEmpty().toList()

    private fun File.text(name: String) = File(this, name).takeIf { it.isFile }?.readText()?.trim()

    @Test
    fun the_listing_is_written_in_every_language_the_app_speaks() {
        val spoken = File(Repo.root, "app/src/main/res").listFiles { file -> Regex("values-[a-z]{2}").matches(file.name) }.orEmpty().map { it.name.removePrefix("values-") }

        assertThat(spoken).isNotEmpty()
        assertThat(listings.map { it.name }).contains("en-US")
        spoken.forEach { language -> assertWithMessage(language).that(listings.any { it.name.startsWith("$language-") }).isTrue() }
    }

    @Test
    fun every_listing_names_the_app_and_stays_within_the_stores_limits() {
        for (listing in listings) {
            assertWithMessage("${listing.name} title").that(listing.text("title.txt")).isEqualTo("Forgeline")
            val short = listing.text("short_description.txt")
            assertWithMessage("${listing.name} short description").that(short).isNotEmpty()
            assertWithMessage("${listing.name} short description").that(short!!.length).isAtMost(80)
            // One line: both stores show it under the name.
            assertWithMessage("${listing.name} short description").that(short).doesNotContain("\n")
            // F-Droid's own checks refuse a summary that ends with a full stop.
            assertWithMessage("${listing.name} short description").that(short.endsWith(".")).isFalse()
            val full = listing.text("full_description.txt")
            assertWithMessage("${listing.name} full description").that(full!!.length).isIn(com.google.common.collect.Range.closed(500, 4000))
        }
    }

    @Test
    fun the_listing_shows_the_app() {
        val screenshots = File(Repo.root, "fastlane/metadata/android/en-US/images/phoneScreenshots").listFiles().orEmpty()

        // Play asks for two at least, and takes eight at most.
        assertThat(screenshots.size).isIn(com.google.common.collect.Range.closed(2, 8))
        screenshots.forEach { assertWithMessage(it.name).that(it.name).matches("""\d+\.png""") }
    }

    @Test
    fun the_listing_promises_nothing_about_tracking_that_the_build_would_break() {
        val catalog = Repo.text("gradle/libs.versions.toml").lowercase()

        // "No analytics, no trackers" is in every listing: none of the usual ones may come in unnoticed.
        listOf("firebase", "play-services", "crashlytics", "com.google.android.gms", "sentry", "appcenter").forEach {
            assertWithMessage(it).that(catalog).doesNotContain(it)
        }
    }
}
