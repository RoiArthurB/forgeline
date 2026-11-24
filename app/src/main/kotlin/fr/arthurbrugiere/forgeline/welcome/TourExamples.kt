package fr.arthurbrugiere.forgeline.welcome

import fr.arthurbrugiere.forgeline.core.model.ConversationPreview
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.FeedPreviews
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.ReleasePreview
import fr.arthurbrugiere.forgeline.core.model.ReleaseRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.feed.FeedUiState
import fr.arthurbrugiere.forgeline.feed.feedItems
import fr.arthurbrugiere.forgeline.inbox.InboxFilter
import fr.arthurbrugiere.forgeline.inbox.InboxSection
import fr.arthurbrugiere.forgeline.inbox.InboxUiState
import fr.arthurbrugiere.forgeline.inbox.SectionGroup
import java.time.Instant

// What the tour's Inbox and Feed show before anyone signs in: made-up projects and people, spread over the forges the app
// reads, so the real screens can be seen and tried. The tour says they are examples; nothing here is sent anywhere.

private val rocket = RepoId("acme", "rocket", ForgeInstance.GitHub)
private val garden = RepoId("you", "garden", ForgeInstance.Codeberg)
private val pipeline = RepoId("tools", "pipeline", ForgeInstance.GitLab)
private val atlas = RepoId("maps", "atlas", ForgeInstance.Gitea)

/** The example threads, each [minutesAgo] old next to [nowMillis], so their times read as just now. */
internal fun exampleThreads(nowMillis: Long): List<NotificationThread> {
    fun ago(minutes: Long) = Instant.ofEpochMilli(nowMillis - minutes * 60_000)
    return listOf(
        NotificationThread(
            "tour-1", rocket, "Retry the handshake on cold start", SubjectType.PULL_REQUEST, 128,
            NotificationReason.REVIEW_REQUESTED, unread = true, updatedAt = ago(4), state = SubjectState.OPEN, accountId = "tour-github",
        ),
        NotificationThread(
            "tour-2", garden, "Crash when the config file is empty", SubjectType.ISSUE, 42,
            NotificationReason.MENTION, unread = true, updatedAt = ago(18), state = SubjectState.OPEN, accountId = "tour-codeberg",
        ),
        NotificationThread(
            "tour-3", pipeline, "Cache the toolchain between jobs", SubjectType.PULL_REQUEST, 7,
            NotificationReason.COMMENT, unread = true, updatedAt = ago(52), state = SubjectState.OPEN, accountId = "tour-gitlab",
        ),
        NotificationThread(
            "tour-4", atlas, "Atlas 3.1", SubjectType.RELEASE, null,
            NotificationReason.SUBSCRIBED, unread = false, updatedAt = ago(190), accountId = "tour-gitea",
        ),
    )
}

/**
 * The Inbox as it would list [threads]: what waits on you first, the rest under it. No hint card: the tour's own words
 * say what the swipes do, and the threads to try stay in sight.
 */
internal fun exampleInbox(threads: List<NotificationThread>): InboxUiState {
    val needsYou = setOf(NotificationReason.REVIEW_REQUESTED, NotificationReason.MENTION)
    val (first, rest) = threads.partition { it.reason in needsYou }
    return InboxUiState(
        filter = InboxFilter.ALL,
        groups = listOfNotNull(
            first.takeIf { it.isNotEmpty() }?.let { SectionGroup(InboxSection.NEEDS_YOU, it) },
            rest.takeIf { it.isNotEmpty() }?.let { SectionGroup(InboxSection.OTHERS, it) },
        ),
        syncedAtMillis = 1,
        showForge = true,
    )
}

/** The example Feed: a release, a merge, a star and a new repository, from people one might follow. */
internal fun exampleFeed(nowMillis: Long): FeedUiState {
    fun ago(minutes: Long) = Instant.ofEpochMilli(nowMillis - minutes * 60_000)
    val tools = RepoId("octo", "tools", ForgeInstance.GitHub)
    val events = listOf(
        FeedEvent("tour-f1", ForgeUser("alice", "Alice", null), tools, FeedAction.Released("v2.0.0", "Tools 2.0: faster everything", prerelease = false), ago(6)),
        FeedEvent("tour-f2", ForgeUser("bob", "Bob", null), garden, FeedAction.PullRequest(PullRequestAction.MERGED, 43), ago(25)),
        FeedEvent("tour-f3", ForgeUser("carol", "Carol", null), rocket, FeedAction.Starred, ago(40)),
        FeedEvent("tour-f4", ForgeUser("dave", "Dave", null), atlas, FeedAction.Starred, ago(70)),
        FeedEvent("tour-f5", ForgeUser("erin", "Erin", null), RepoId("erin", "lumen", ForgeInstance.Codeberg), FeedAction.CreatedRepo("A tiny static site generator that builds in milliseconds"), ago(130)),
    )
    return FeedUiState(
        items = feedItems(events, FeedKind.defaults),
        syncedAtMillis = 1,
        showForge = true,
        previews = FeedPreviews(
            repos = mapOf(
                rocket to RepoPreview("Launch orchestration for tiny satellites, in pure Rust.", "Rust", 12_400, ago(30)),
                atlas to RepoPreview("Offline maps you can carry in your pocket.", "Go", 3_150, ago(240)),
            ),
            releases = mapOf(
                ReleaseRef(tools, "v2.0.0") to ReleasePreview(
                    "Every command starts twice as fast, and the config file finally has a schema.", "v1.9.2", null, null,
                    mapOf(Reaction.HOORAY to 31, Reaction.ROCKET to 12),
                ),
            ),
            conversations = mapOf(
                IssueRef(garden, 43, isPullRequest = true) to ConversationPreview(
                    "Water the plants on a schedule", additions = 212, deletions = 40, changedFiles = 7,
                    labels = listOf(Label("feature", "0e8a16")),
                ),
            ),
        ),
    )
}
