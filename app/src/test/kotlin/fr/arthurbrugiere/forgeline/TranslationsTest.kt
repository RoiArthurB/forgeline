package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.you.YouScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every language Forgeline is written in says everything English does, with the same blanks to fill: a translation
 * that forgets a string shows English in the middle of a sentence, and one that forgets a blank crashes when it is filled.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val res = File("src/main/res")
    private val languages = res.listFiles { file -> file.isDirectory && Regex("values-[a-z]{2}").matches(file.name) }!!.map { it.name.removePrefix("values-") }

    /** What a file says, by name; a plural is one entry per quantity ("name/one"). What isn't to be translated is left out. */
    private fun strings(file: File): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
        return buildMap {
            children.filter { it.getAttribute("translatable") != "false" }.forEach { element ->
                val name = element.getAttribute("name")
                when (element.tagName) {
                    "string" -> put(name, element.textContent)
                    "plurals" -> (0 until element.childNodes.length).map { element.childNodes.item(it) }.filterIsInstance<Element>()
                        .forEach { put("$name/${it.getAttribute("quantity")}", it.textContent) }
                }
            }
        }
    }

    private fun blanks(text: String): List<String> = Regex("""%(\d+\$)?[sd]""").findAll(text).map { it.value }.sorted().toList()

    private val english = strings(File(res, "values/strings.xml"))

    @Test
    fun french_is_one_of_the_languages() {
        assertThat(languages).contains("fr")
    }

    @Test
    fun every_language_says_everything_english_does_and_nothing_else() {
        languages.forEach { language ->
            val translated = strings(File(res, "values-$language/strings.xml"))
            // A language may count in more ways than English does (French sets millions apart).
            val names = translated.keys.filterNot { it.endsWith("/many") || it.endsWith("/few") || it.endsWith("/two") || it.endsWith("/zero") }.toSet()

            assertWithMessage("strings of $language").that(names).isEqualTo(english.keys)
            assertWithMessage("empty strings of $language").that(translated.filterValues { it.isBlank() }.keys).isEmpty()
        }
    }

    @Test
    fun every_translation_has_the_blanks_of_the_english_it_stands_for() {
        languages.forEach { language ->
            strings(File(res, "values-$language/strings.xml")).forEach { (name, text) ->
                val source = english[name] ?: english[name.substringBefore('/') + "/other"] ?: return@forEach
                // The one plural of a language may name the count in words ("the error") where others give it.
                if (name.endsWith("/one") && blanks(text).size < blanks(source).size) return@forEach
                assertWithMessage("blanks of $name in $language").that(blanks(text)).isEqualTo(blanks(source))
            }
        }
    }

    @Test
    fun every_plural_has_the_quantities_its_language_counts_with() {
        val needed = mapOf("fr" to setOf("one", "many", "other"))
        languages.forEach { language ->
            val translated = strings(File(res, "values-$language/strings.xml"))
            english.keys.filter { it.endsWith("/other") }.map { it.substringBefore('/') }.forEach { plural ->
                val quantities = translated.keys.filter { it.startsWith("$plural/") }.map { it.substringAfter('/') }.toSet()
                assertWithMessage("quantities of $plural in $language").that(quantities).containsAtLeastElementsIn(needed[language] ?: setOf("other"))
            }
        }
    }

    @Test
    fun android_is_told_every_language_so_it_can_offer_them_for_the_app() {
        val config = File(res, "xml/locales_config.xml").readText()
        val offered = Regex("""android:name="([a-z-]+)"""").findAll(config).map { it.groupValues[1] }.toList()

        assertThat(offered).containsExactlyElementsIn(listOf("en") + languages)
    }

    @Test
    @Config(qualifiers = "fr-w412dp-h915dp-xxhdpi")
    fun a_phone_set_to_french_reads_in_french() {
        val account = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))
        composeRule.setContent { ForgelineTheme { YouScreen(SessionState.SignedIn(account), onSignIn = {}, onOpenSettings = {}) } }

        composeRule.onNodeWithText("Vous").assertIsDisplayed()
        composeRule.onNodeWithText("Réglages").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr")
    fun french_counts_and_fills_its_blanks() {
        val resources = ApplicationProvider.getApplicationContext<android.app.Application>().resources

        assertThat(resources.getQuantityString(R.plurals.repo_comments, 1, 1)).isEqualTo("1 commentaire")
        assertThat(resources.getQuantityString(R.plurals.repo_comments, 3, 3)).isEqualTo("3 commentaires")
        // French counts nothing as one.
        assertThat(resources.getQuantityString(R.plurals.repo_comments, 0, 0)).isEqualTo("0 commentaire")
        assertThat(resources.getString(R.string.feed_member_added, "alice", "octo/tools", "bob")).isEqualTo("alice a ajouté bob à octo/tools")
        // The brand keeps its name.
        assertThat(resources.getString(R.string.app_name)).isEqualTo("Forgeline")
    }

    @Test
    @Config(qualifiers = "de")
    fun a_language_forgeline_is_not_written_in_reads_in_english() {
        val resources = ApplicationProvider.getApplicationContext<android.app.Application>().resources

        assertThat(resources.getString(R.string.settings_title)).isEqualTo("Settings")
    }
}
