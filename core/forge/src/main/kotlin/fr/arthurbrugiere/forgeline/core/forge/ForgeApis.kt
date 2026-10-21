package fr.arthurbrugiere.forgeline.core.forge

/** Every API of one forge: what [ForgeClients] picks from, for the forge a call is about. */
interface ForgeApis {
    val repos: RepoApi
    val issues: IssueApi
    val pulls: PullRequestApi
    val repoSettings: RepoSettingsApi
    val users: UserApi
    val stars: StarApi
    val search: SearchApi
    val feed: FeedApi
    val notifications: NotificationsApi
    val auth: ForgeAuthApi

    /** Null when the forge has no CI API Forgeline can use. */
    val actions: ActionsApi?

    /** Null when the forge has no trending list of its own. */
    val trending: TrendingApi?

    /** What measures the forge's Trending on the phone; null when it can't be measured. */
    val trendingMeter: TrendingMeter?
}
