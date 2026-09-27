package fr.arthurbrugiere.forgeline.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How often the Inbox is checked in the background for new notifications. */
enum class InboxCheckInterval(val minutes: Long?) { OFF(null), MIN_15(15), MIN_30(30), HOUR_1(60), HOUR_3(180), HOUR_6(360) }

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val amoledBlack: Boolean = false,
    val inboxCheckInterval: InboxCheckInterval = InboxCheckInterval.HOUR_1,
)
