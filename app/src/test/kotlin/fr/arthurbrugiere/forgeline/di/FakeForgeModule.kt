package fr.arthurbrugiere.forgeline.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.testing.FakeActionsApi
import fr.arthurbrugiere.forgeline.core.testing.FakeFeedApi
import fr.arthurbrugiere.forgeline.core.testing.feedEvent
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.core.testing.FakeNotificationsApi
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.FakeUserApi
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import fr.arthurbrugiere.forgeline.core.testing.userProfile
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.testing.FakeSearchApi
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoApi
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.core.testing.repoDetails
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeAuthApi
import fr.arthurbrugiere.forgeline.core.testing.FakeStarApi
import fr.arthurbrugiere.forgeline.core.testing.FakeTrendingApi
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import javax.inject.Singleton

/** App-level tests never touch the network: forge clients are fakes with a fixed ranking. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ForgeModule::class])
object FakeForgeModule {
    @Provides
    @Singleton
    fun provideTrendingApi(): TrendingApi = FakeTrendingApi().apply {
        results[TrendingPeriod.DAILY] = ForgeResult.Success(
            listOf(trendingRepo("paperclipai/paperclip", stars = 85_955, periodStars = 2_109), trendingRepo("vectorize-io/hindsight")),
        )
        results[TrendingPeriod.WEEKLY] = ForgeResult.Success(listOf(trendingRepo("NVIDIA/Model-Optimizer")))
    }

    @Provides
    @Singleton
    fun provideStarApi(): StarApi = FakeStarApi().apply { forkedTo = RepoId("octocat", "paperclip") }

    @Provides
    @Singleton
    fun provideRepoApi(): RepoApi = FakeRepoApi().apply {
        val paperclip = RepoId("paperclipai", "paperclip")
        details[paperclip] = repoDetails("paperclipai/paperclip", defaultBranch = "master", stars = 85_955).copy(hasDiscussions = true, hasWiki = true)
        // Where a fork of it lands.
        details[RepoId("octocat", "paperclip")] = repoDetails("octocat/paperclip", defaultBranch = "master").copy(description = "A fork to try things in")
        val asked = fr.arthurbrugiere.forgeline.core.testing.discussionSummary(412, "How do I page through runs?", comments = 1, isAnswered = true)
        discussionPages[null] = fr.arthurbrugiere.forgeline.core.model.DiscussionPage(listOf(asked), next = null)
        discussions[412] = fr.arthurbrugiere.forgeline.core.model.Discussion(
            asked, "The list stops at 30 runs.",
            listOf(fr.arthurbrugiere.forgeline.core.testing.discussionComment("c1", "Use the cursor the list gives back.", isAnswer = true)),
        )
        readmes[paperclip] = Readme("README.md", "# Paperclip\n\nOpen-source orchestration for teams of AI agents.")
        issues = listOf(
            issueSummary(14127, "Heartbeat recovery escalates too early"),
            issueSummary(13990, "Installer ignores the proxy setting", state = fr.arthurbrugiere.forgeline.core.model.IssueState.CLOSED),
        )
        pinned = listOf(issueSummary(12000, "Read this before reporting a bug"))
        releases = listOf(
            fr.arthurbrugiere.forgeline.core.model.Release(
                tag = "v2026.916.1", name = "September", body = "Heartbeats recover on their own.", publishedAt = null, isPrerelease = false, author = null,
                assets = listOf(fr.arthurbrugiere.forgeline.core.model.ReleaseAsset("paperclip-linux-amd64", 6_081_740, 144, "https://example.org/paperclip-linux-amd64")),
                zipUrl = "https://example.org/paperclip.zip", isLatest = true,
            ),
        )
        pulls = listOf(issueSummary(14129, "Keep install flags on retry", isPullRequest = true))
        directories[paperclip to ""] = listOf(RepoFile("package.json", "package.json", RepoFileType.FILE, 30))
        files[paperclip to "package.json"] = "{\n  \"name\": \"paperclip\"\n}\n"
    }

    @Provides
    @Singleton
    fun provideActionsApi(): ActionsApi = FakeActionsApi()

    @Provides
    @Singleton
    fun provideIssueApi(): IssueApi = FakeIssueApi().apply {
        val ref = IssueRef(RepoId("paperclipai", "paperclip"), 14127)
        issues[ref] = issueDetails(ref, "Heartbeat recovery escalates too early")
        pages[ref to 1] = TimelinePage(
            listOf(comment(1, "I can reproduce this on every restart.", login = "hubot"), comment(2, "Mine happens after an update.", login = "octocat")),
            null,
        )
        // A long one, in two pages, that the Inbox has a thread about.
        val long = IssueRef(ref.repo, 14129)
        issues[long] = issueDetails(long, "Keep install flags on retry")
        val start = java.time.Instant.parse("2026-09-26T09:00:00Z")
        fun remark(n: Int) = comment(n.toLong(), "Remark $n on the retry.", login = "hubot").copy(createdAt = start.plusSeconds(n * 60L))
        pages[long to 1] = TimelinePage((1..20).map(::remark), nextPage = 2, lastPage = 2)
        pages[long to 2] = TimelinePage((21..40).map(::remark), nextPage = null)
        // Whoever signs in owns the repository: everything can be managed.
        access[ref.repo] = fr.arthurbrugiere.forgeline.core.model.RepoAccess.ADMIN
    }

    @Provides
    @Singleton
    fun provideUserApi(): UserApi = FakeUserApi().apply {
        users["octocat"] = userProfile("octocat", name = "The Octocat")
    }

    @Provides
    @Singleton
    fun provideNotificationsApi(): NotificationsApi = FakeNotificationsApi().apply {
        threads = listOf(
            notificationThread("14127", repo = "paperclipai/paperclip", title = "Heartbeat recovery escalates too early"),
            // Last read after its 30th remark.
            notificationThread("14129", repo = "paperclipai/paperclip", title = "Keep install flags on retry", type = SubjectType.PULL_REQUEST)
                .copy(lastReadAt = java.time.Instant.parse("2026-09-26T09:30:30Z")),
            // Only watched: listed under their repository's heading.
            notificationThread("301", repo = "octo/tools", title = "Flaky upload on slow links", reason = fr.arthurbrugiere.forgeline.core.model.NotificationReason.SUBSCRIBED),
            notificationThread("302", repo = "octo/tools", title = "Bump the SDK", reason = fr.arthurbrugiere.forgeline.core.model.NotificationReason.SUBSCRIBED),
        )
    }

    @Provides
    @Singleton
    fun provideSearchApi(): SearchApi = FakeSearchApi().apply {
        repositories = listOf(repoSummary("paperclipai/paperclip", stars = 85_955, description = "Open-source orchestration for teams of AI agents"))
        users = listOf(UserSummary("octocat", null, isOrganization = false))
    }

    @Provides
    @Singleton
    fun provideFeedApi(): FeedApi = FakeFeedApi().apply {
        pages[1] = listOf(
            feedEvent("2", actor = "hubot", repo = "paperclipai/paperclip", createdAt = "2026-09-27T09:00:00Z"),
            feedEvent(
                "1", actor = "octocat", repo = "paperclipai/paperclip", createdAt = "2026-09-27T08:00:00Z",
                action = FeedAction.Issue(IssueAction.OPENED, 14127, "Heartbeat recovery escalates too early"),
            ),
        )
    }

    @Provides
    @Singleton
    fun provideForgeAuthApi(): ForgeAuthApi = FakeForgeAuthApi(supportsDeviceFlow = false)
}
