package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance

/**
 * The API clients for each forge. Forgeline talks to several forges at once (github.com, codeberg.org, self-hosted
 * Forgejo), so every call picks its client from the forge of what it's about: a repository's, an account's.
 */
interface ForgeClients {
    fun repos(forge: ForgeInstance): RepoApi

    fun issues(forge: ForgeInstance): IssueApi

    fun users(forge: ForgeInstance): UserApi

    fun stars(forge: ForgeInstance): StarApi

    fun search(forge: ForgeInstance): SearchApi

    fun feed(forge: ForgeInstance): FeedApi

    fun notifications(forge: ForgeInstance): NotificationsApi

    fun auth(forge: ForgeInstance): ForgeAuthApi

    /** Null when the forge has no CI API Forgeline can use. */
    fun actions(forge: ForgeInstance): ActionsApi?

    /** Null when the forge has no trending list of its own. */
    fun trending(forge: ForgeInstance): TrendingApi?

    /** What measures the forge's Trending on the phone; null when it can't be measured (GitHub has its own list). */
    fun trendingMeter(forge: ForgeInstance): TrendingMeter?
}
