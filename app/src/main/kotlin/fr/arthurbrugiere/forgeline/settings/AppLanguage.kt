package fr.arthurbrugiere.forgeline.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language Forgeline alone is shown in, kept by Android itself (Android 13 and later), so it is the same choice
 * as the one under the app's page in the system settings. Before Android 13 the app follows the phone's language.
 */
object AppLanguage {
    /** The languages Forgeline is written in; `res/xml/locales_config.xml` tells Android the same list. */
    val tags = listOf("en", "fr")

    val canBeChosen: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The language chosen for the app, or null when it follows the phone's. */
    fun chosen(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        return if (locales.isEmpty) null else locales[0].language.takeIf { it in tags }
    }

    /** Shows the app in [tag]'s language, or in the phone's when null. Android restarts the screen to apply it. */
    fun choose(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        context.getSystemService(LocaleManager::class.java)?.applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    /** A language's name in that language ("Français"), which is how someone looking for it reads it. */
    fun name(tag: String): String = Locale.forLanguageTag(tag).let { locale ->
        locale.getDisplayLanguage(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
