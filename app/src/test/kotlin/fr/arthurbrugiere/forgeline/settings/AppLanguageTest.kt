package fr.arthurbrugiere.forgeline.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppLanguageTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun the_languages_offered_are_those_android_is_told_and_those_translated() {
        val told = Regex("""android:name="([a-z-]+)"""").findAll(File("src/main/res/xml/locales_config.xml").readText()).map { it.groupValues[1] }.toList()
        val translated = File("src/main/res").listFiles { file -> Regex("values-[a-z]{2}").matches(file.name) }!!.map { it.name.removePrefix("values-") }

        assertThat(AppLanguage.tags).containsExactlyElementsIn(told)
        assertThat(AppLanguage.tags).containsExactlyElementsIn(listOf("en") + translated)
    }

    @Test
    fun a_language_is_named_in_itself() {
        assertThat(AppLanguage.name("fr")).isEqualTo("Français")
        assertThat(AppLanguage.name("en")).isEqualTo("English")
    }

    @Test
    fun untouched_the_app_follows_the_phone() {
        assertThat(AppLanguage.canBeChosen).isTrue()
        assertThat(AppLanguage.chosen(context)).isNull()
    }

    @Test
    fun a_language_chosen_is_kept_by_android_and_can_be_handed_back() {
        AppLanguage.choose(context, "fr")
        assertThat(AppLanguage.chosen(context)).isEqualTo("fr")

        AppLanguage.choose(context, null)
        assertThat(AppLanguage.chosen(context)).isNull()
    }

    @Test
    @Config(sdk = [32])
    fun before_android_13_no_language_can_be_chosen_and_asking_changes_nothing() {
        assertThat(AppLanguage.canBeChosen).isFalse()

        AppLanguage.choose(context, "fr")

        assertThat(AppLanguage.chosen(context)).isNull()
    }
}
