package fr.arthurbrugiere.forgeline.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import fr.arthurbrugiere.forgeline.ui.LocalOpenSearch
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.snapshots.Snapshot
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import androidx.compose.runtime.mutableStateOf
import com.github.takahirom.roborazzi.captureRoboImage
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.signin.SignInScreen
import fr.arthurbrugiere.forgeline.signin.SignInStep
import fr.arthurbrugiere.forgeline.signin.SignInUiState
import fr.arthurbrugiere.forgeline.you.YouScreen
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.feed.FeedScreen
import fr.arthurbrugiere.forgeline.feed.FeedUiState
import fr.arthurbrugiere.forgeline.feed.feedItems
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.testing.feedEvent
import fr.arthurbrugiere.forgeline.search.SearchScreen
import fr.arthurbrugiere.forgeline.search.SearchUiState
import fr.arthurbrugiere.forgeline.search.ScopeResults
import fr.arthurbrugiere.forgeline.search.SearchResult
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.SearchScope
import fr.arthurbrugiere.forgeline.inbox.InboxScreen
import fr.arthurbrugiere.forgeline.inbox.InboxUiState
import fr.arthurbrugiere.forgeline.inbox.NotificationPrompt
import fr.arthurbrugiere.forgeline.inbox.InboxAction
import fr.arthurbrugiere.forgeline.inbox.InboxFilter
import fr.arthurbrugiere.forgeline.inbox.InboxSection
import fr.arthurbrugiere.forgeline.inbox.PendingUndo
import fr.arthurbrugiere.forgeline.inbox.SectionGroup
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import fr.arthurbrugiere.forgeline.issue.IssueScreen
import fr.arthurbrugiere.forgeline.issue.MARKDOWN_PENDING_TAG
import fr.arthurbrugiere.forgeline.issue.IssueUiState
import fr.arthurbrugiere.forgeline.user.UserScreen
import fr.arthurbrugiere.forgeline.user.UserUiState
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import fr.arthurbrugiere.forgeline.core.testing.userProfile
import fr.arthurbrugiere.forgeline.file.CODE_HIGHLIGHTED_TAG
import fr.arthurbrugiere.forgeline.file.FileContent
import fr.arthurbrugiere.forgeline.file.FileScreen
import fr.arthurbrugiere.forgeline.file.FileTarget
import fr.arthurbrugiere.forgeline.file.FileUiState
import fr.arthurbrugiere.forgeline.repo.RepoScreen
import fr.arthurbrugiere.forgeline.repo.RepoTab
import fr.arthurbrugiere.forgeline.repo.RepoUiState
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.core.testing.repoDetails
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import fr.arthurbrugiere.forgeline.trending.TrendingItem
import fr.arthurbrugiere.forgeline.trending.TrendingScreen
import fr.arthurbrugiere.forgeline.trending.TrendingUiState
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.settings.SettingsScreen
import fr.arthurbrugiere.forgeline.ui.ForgelineApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun snapshot(
        name: String,
        darkTheme: Boolean,
        amoledBlack: Boolean = false,
        awaitText: String? = null,
        awaitTag: String? = null,
        awaitGoneTag: String? = null,
        beforeCapture: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            // Top-level screens get their search action as they do inside the app shell.
            CompositionLocalProvider(LocalOpenSearch provides {}) {
                ForgelineTheme(darkTheme = darkTheme, amoledBlack = amoledBlack, content = content)
            }
        }
        // Content parsed off the main thread (READMEs) must be on screen before capturing.
        awaitText?.let { text ->
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
        awaitTag?.let { tag ->
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        }
        awaitGoneTag?.let { tag ->
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        }
        beforeCapture()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun app_shell_light() = snapshot("app_shell_light", darkTheme = false) { ForgelineApp(session = SessionState.SignedOut, onSignOut = {}) }

    @Test
    fun app_shell_dark() = snapshot("app_shell_dark", darkTheme = true) { ForgelineApp(session = SessionState.SignedOut, onSignOut = {}) }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun app_shell_tablet() = snapshot("app_shell_tablet", darkTheme = false) { ForgelineApp(session = SessionState.SignedOut, onSignOut = {}) }

    @Test
    fun settings_light() = snapshot("settings_light", darkTheme = false) { SettingsPreview() }

    @Test
    fun settings_dark_amoled() = snapshot("settings_dark_amoled", darkTheme = true, amoledBlack = true) {
        SettingsPreview(UserSettings(themeMode = ThemeMode.DARK, amoledBlack = true))
    }

    @Test
    fun sign_in_light() = snapshot("sign_in_light", darkTheme = false) {
        SignInScreen(
            state = SignInUiState(true, "https://github.com/settings/tokens/new"),
            onStartDeviceFlow = {}, onContinueOnGitHub = { _, _ -> }, onSubmitToken = {}, onOpenUrl = {},
            onCancel = {}, onDismissError = {}, onBack = {},
        )
    }

    @Test
    fun sign_in_device_code_dark() = snapshot("sign_in_device_code_dark", darkTheme = true) {
        SignInScreen(
            state = SignInUiState(true, "", SignInStep.AwaitingAuthorization("WDJB-MJHT", "https://github.com/login/device")),
            onStartDeviceFlow = {}, onContinueOnGitHub = { _, _ -> }, onSubmitToken = {}, onOpenUrl = {},
            onCancel = {}, onDismissError = {}, onBack = {},
        )
    }

    @Test
    fun you_signed_in_light() = snapshot("you_signed_in_light", darkTheme = false) {
        YouScreen(SessionState.SignedIn(octocat), onSignIn = {}, onOpenSettings = {})
    }

    // Synthetic list: plausible names, languages and counts, not real GitHub data.
    private val trendingState = TrendingUiState(
        items = listOf(
            TrendingItem(
                trendingRepo("paperclipai/paperclip", stars = 85_955, periodStars = 2_109)
                    .copy(
                        description = "Open-source orchestration for teams of AI agents: plans, budgets and a shared memory.",
                        language = "TypeScript", languageColor = "#3178c6", forks = 4_210,
                        builtBy = listOf("cryppadotta", "devinfoley", "nickyleach").map { ForgeUser(it, null, null) },
                    ),
                starred = true,
            ),
            TrendingItem(
                trendingRepo("vectorize-io/hindsight", stars = 30_641, periodStars = 1_653)
                    .copy(description = "Agent memory that learns from every run and forgets what stopped mattering.", language = "Python", languageColor = "#3572A5", forks = 1_122),
                starred = false,
            ),
            TrendingItem(trendingRepo("anthropics/claude-code-action", stars = 8_970, periodStars = 415, description = null).copy(language = "TypeScript", languageColor = "#3178c6", forks = 612), starred = false),
            TrendingItem(
                trendingRepo("tokio-rs/tokio-console", stars = 3_812, periodStars = 208)
                    .copy(description = "A debugger for async Rust programs.", language = "Rust", languageColor = "#dea584", forks = 164),
                starred = false,
            ),
            TrendingItem(
                trendingRepo("mitchellh/ghostty-themes", stars = 1_204, periodStars = 97)
                    .copy(description = "Color themes for Ghostty, generated from their iTerm2 originals.", language = "Zig", languageColor = "#ec915c", forks = 58),
                starred = false,
            ),
        ),
        updatedAtMillis = 0L,
    )

    @Test
    fun trending_light() = snapshot("trending_light", darkTheme = false) { TrendingPreview() }

    @Test
    fun trending_dark() = snapshot("trending_dark", darkTheme = true) { TrendingPreview() }

    @Test
    fun trending_resume_dark_amoled() = snapshot("trending_resume_dark_amoled", darkTheme = true, amoledBlack = true) {
        TrendingPreview(trendingState.copy(resumeAt = 1))
    }

    private fun pressHindsight() {
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("hindsight").performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(300)
    }

    @Test
    fun trending_row_pressed_light() = snapshot("trending_row_pressed_light", darkTheme = false, beforeCapture = ::pressHindsight) { TrendingPreview() }

    @Test
    fun trending_row_pressed_dark() = snapshot("trending_row_pressed_dark", darkTheme = true, beforeCapture = ::pressHindsight) { TrendingPreview() }

    @Test
    fun trending_resume_light() = snapshot("trending_resume_light", darkTheme = false) { TrendingPreview(trendingState.copy(resumeAt = 1)) }

    @Test
    fun trending_empty_light() = snapshot("trending_empty_light", darkTheme = false) { TrendingPreview(TrendingUiState(updatedAtMillis = 0L)) }

    // Large font scales on a small phone: labels grow or shrink to fit, nothing is cut off.
    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 1.3f)
    fun trending_font_1_3_light() = snapshot("trending_font_1_3_light", darkTheme = false) { TrendingPreview() }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 1.3f)
    fun trending_font_1_3_dark() = snapshot("trending_font_1_3_dark", darkTheme = true) { TrendingPreview() }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun trending_font_2_0_light() = snapshot("trending_font_2_0_light", darkTheme = false) { TrendingPreview() }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun trending_font_2_0_dark() = snapshot("trending_font_2_0_dark", darkTheme = true) { TrendingPreview() }

    @Test
    fun trending_loading_light() = snapshot("trending_loading_light", darkTheme = false) { TrendingPreview(TrendingUiState()) }

    @Test
    fun trending_error_dark() = snapshot("trending_error_dark", darkTheme = true) {
        TrendingPreview(TrendingUiState(error = ForgeError.Network))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun trending_tablet_light() = snapshot("trending_tablet_light", darkTheme = false) { TrendingPreview() }

    @Test
    fun trending_period_change_mid_light() {
        val period = mutableStateOf(TrendingPeriod.DAILY)
        snapshot(
            "trending_period_change_mid_light",
            darkTheme = false,
            beforeCapture = {
                composeRule.mainClock.autoAdvance = false
                period.value = TrendingPeriod.WEEKLY
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.mainClock.advanceTimeBy(120)
            },
        ) { TrendingPreview(trendingState.copy(period = period.value)) }
    }

    @Composable
    private fun TrendingPreview(state: TrendingUiState = trendingState) {
        TrendingScreen(
            state = state, onPeriodChange = {}, onRefresh = {}, onToggleStar = {}, onOpenRepo = {},
            onErrorShown = {}, onStarFailureShown = {}, nowMillis = 12 * 60_000L,
        )
    }

    private val repoState = RepoUiState(
        requested = RepoId("paperclipai", "paperclip"),
        details = repoDetails("paperclipai/paperclip", defaultBranch = "master", stars = 85_955)
            .copy(description = "The open-source app everyone uses to manage agents at work", topics = listOf("agents", "orchestration", "typescript")),
        readme = Readme("README.md", "# Paperclip\n\nOpen-source orchestration for **teams of AI agents**.\n\n```bash\nnpx paperclip init\n```"),
        readmeContext = ReadmeContext("https://raw.example/", "https://blob.example/"),
        starred = true,
    )

    @Test
    fun repo_readme_light() = snapshot("repo_readme_light", darkTheme = false, awaitText = "teams of AI agents") {
        RepoPreview(repoState)
    }

    @Test
    fun repo_issues_dark() = snapshot("repo_issues_dark", darkTheme = true) {
        RepoPreview(
            repoState.copy(
                tab = RepoTab.ISSUES,
                issues = Loadable.Loaded(listOf(issueSummary(14127, "Heartbeat recovery escalates too early"), issueSummary(14121, "Wake-queue reopen activity record names"))),
            ),
        )
    }

    @Test
    fun file_code_dark() = snapshot("file_code_dark", darkTheme = true, awaitTag = CODE_HIGHLIGHTED_TAG) {
        FileScreen(
            state = FileUiState(
                target = FileTarget(RepoId("octo", "repo"), "src/Main.kt", "main"),
                content = Loadable.Loaded(
                    FileContent.Text("package demo\n\n// Entry point\nfun main() {\n    val answer = 42\n    println(\"Answer: \$answer\")\n}\n"),
                ),
                webUrl = "https://github.com/octo/repo/blob/main/src/Main.kt",
                readmeContext = ReadmeContext("https://raw.example/", "https://blob.example/"),
            ),
            onBack = {}, onRetry = {}, onOpenInBrowser = {}, onLinkClick = {}, onCopy = {},
        )
    }

    @Test
    fun issue_light() = snapshot("issue_light", darkTheme = false, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        val ref = IssueRef(RepoId("paperclipai", "paperclip"), 14127)
        val at = java.time.Instant.parse("2026-09-26T08:00:00Z")
        IssueScreen(
            state = IssueUiState(
                ref = ref,
                issue = issueDetails(ref, "Heartbeat recovery escalates too early")
                    .copy(labels = listOf(Label("bug", "d73a4a")), reactions = mapOf(Reaction.THUMBS_UP to 4)),
                items = listOf(
                    comment(1, "Same here on 2026.9, it happens after every restart.", login = "hubot"),
                    TimelineItem.StateChanged(StateChange.CLOSED, ForgeUser("maintainer", null, null), "completed", at),
                ),
            ),
            onBack = {}, onRefresh = {}, onLoadMore = {}, onOpenIssue = {}, onOpenUser = {}, onOpenInBrowser = {},
            onLinkClick = {}, onErrorShown = {}, nowMillis = java.time.Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
        )
    }

    @Test
    fun user_dark() = snapshot("user_dark", darkTheme = true) {
        UserScreen(
            state = UserUiState(
                login = "octocat",
                profile = userProfile("octocat", name = "The Octocat").copy(location = "San Francisco", website = "https://github.blog"),
                repos = Loadable.Loaded(listOf(RepoSummary(RepoId("octocat", "Hello-World"), "My first repository on GitHub!", "Kotlin", 2_500, 10, false, null))),
                following = false,
            ),
            signedIn = true, onBack = {}, onSelectTab = {}, onRetry = {}, onToggleFollow = {}, onSignIn = {}, onOpenRepo = {},
            onOpenUrl = {}, onFollowFailureShown = {},
        )
    }

    private val inboxSections = listOf(
        SectionGroup(
            InboxSection.NEEDS_YOU,
            listOf(
                notificationThread("42", repo = "acme/rocket", title = "Launch fails on cold start", updatedAt = "2026-09-27T09:30:00Z"),
                notificationThread(
                    "43", repo = "acme/rocket", title = "Add retry to the fuel pump", type = SubjectType.PULL_REQUEST,
                    reason = NotificationReason.REVIEW_REQUESTED, updatedAt = "2026-09-27T09:00:00Z",
                ),
            ),
        ),
        SectionGroup(
            InboxSection.OTHERS,
            listOf(
                notificationThread(
                    "44", repo = "acme/rocket", title = "Cold start docs are out of date", reason = NotificationReason.COMMENT,
                    updatedAt = "2026-09-27T08:00:00Z",
                ),
                notificationThread(
                    "9", repo = "octo/tools", title = "v2.0.0", type = SubjectType.RELEASE, number = null,
                    reason = NotificationReason.SUBSCRIBED, unread = false, updatedAt = "2026-09-26T18:00:00Z",
                ),
            ),
        ),
    )

    @Test
    fun inbox_light() = snapshot("inbox_light", darkTheme = false) {
        InboxScreen(
            state = InboxUiState(filter = InboxFilter.ALL, groups = inboxSections, syncedAtMillis = 1),
            onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {}, onMarkDone = {}, onUnsubscribe = {},
            onErrorShown = {}, onActionFailureShown = {}, nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
        )
    }

    @Test
    fun inbox_undo_dark() = snapshot("inbox_undo_dark", darkTheme = true, awaitText = "Undo") {
        InboxScreen(
            state = InboxUiState(
                filter = InboxFilter.ALL,
                groups = inboxSections,
                syncedAtMillis = 1,
                undo = PendingUndo("45", InboxAction.DONE, serial = 1),
            ),
            onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {}, onMarkDone = {}, onUnsubscribe = {},
            onErrorShown = {}, onActionFailureShown = {}, nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
        )
    }

    @Test
    fun inbox_notification_prompt_dark() = snapshot("inbox_notification_prompt_dark", darkTheme = true) {
        InboxScreen(
            state = InboxUiState(
                groups = listOf(SectionGroup(InboxSection.NEEDS_YOU, listOf(notificationThread("42", title = "Launch fails on cold start")))),
                syncedAtMillis = 1,
            ),
            notificationPrompt = NotificationPrompt.ASK,
            onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {}, onMarkDone = {}, onUnsubscribe = {},
            onErrorShown = {}, onActionFailureShown = {}, nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
        )
    }

    @Test
    fun feed_light() = snapshot("feed_light", darkTheme = false) { FeedPreview() }

    @Test
    fun feed_dark() = snapshot("feed_dark", darkTheme = true) { FeedPreview() }

    @Composable
    private fun FeedPreview() {
        val events = listOf(
            feedEvent("9", actor = "alice", createdAt = "2026-09-27T09:50:00Z"),
            feedEvent("8", actor = "bob", repo = "acme/rocket", createdAt = "2026-09-27T09:45:00Z", action = FeedAction.PullRequest(PullRequestAction.MERGED, 43)),
            feedEvent("7", actor = "carol", createdAt = "2026-09-27T09:40:00Z", action = FeedAction.Issue(IssueAction.CLOSED, 42, "Launch fails on cold start")),
            feedEvent("6", actor = "bob", createdAt = "2026-09-27T09:30:00Z"),
            feedEvent("5", actor = "dave", createdAt = "2026-09-27T09:20:00Z"),
            feedEvent(
                "4", actor = "alice", repo = "octo/tools", createdAt = "2026-09-27T09:10:00Z",
                action = FeedAction.Released("v2.0.0", "Tools 2.0: faster everything", prerelease = false),
            ),
            feedEvent("3", actor = "carol", repo = "octo/tools", createdAt = "2026-09-27T08:20:00Z", action = FeedAction.Forked(RepoId("carol", "tools"))),
            feedEvent("2", actor = "alice", repo = "alice/new-idea", createdAt = "2026-09-26T18:00:00Z", action = FeedAction.CreatedRepo("A fresh idea for faster builds")),
        )
        FeedScreen(
            state = FeedUiState(
                items = feedItems(events, FeedKind.defaults),
                syncedAtMillis = 1,
                previews = fr.arthurbrugiere.forgeline.core.model.FeedPreviews(
                    repos = mapOf(
                        RepoId("acme", "rocket") to fr.arthurbrugiere.forgeline.core.model.RepoPreview(
                            "Launch orchestration for tiny satellites, in pure Rust.", "Rust", 12_400,
                        ),
                    ),
                    pullTitles = mapOf(
                        fr.arthurbrugiere.forgeline.core.model.IssueRef(RepoId("acme", "rocket"), 43) to "Retry the fuel pump handshake on cold start",
                    ),
                ),
            ),
            onRefresh = {}, onLoadMore = {}, onOpenRepo = {}, onOpenIssue = {}, onOpenUser = {}, onErrorShown = {},
            nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
            zone = java.time.ZoneOffset.UTC,
        )
    }

    @Test
    fun search_issues_dark() = snapshot("search_issues_dark", darkTheme = true) {
        SearchScreen(
            state = SearchUiState(
                query = "heartbeat",
                scope = SearchScope.ISSUES,
                results = ScopeResults(
                    query = "heartbeat",
                    items = listOf(
                        SearchResult.Issue(IssueSearchResult(RepoId("paperclipai", "paperclip"), issueSummary(12299, "Cancellation can overwrite a concurrently finalized heartbeat run"))),
                        SearchResult.Issue(IssueSearchResult(RepoId("paperclipai", "paperclip"), issueSummary(13973, "fix(heartbeat): claim task ownership with queued runs", isPullRequest = true))),
                        SearchResult.Issue(IssueSearchResult(RepoId("acme", "rocket"), issueSummary(42, "Heartbeat timer drifts after sleep"))),
                    ),
                    totalCount = 5_322,
                ),
            ),
            onQueryChange = {}, onSubmit = {}, onSelectScope = {}, onLoadMore = {}, onRetry = {},
            onOpenRepo = {}, onOpenIssue = {}, onOpenUser = {}, onBack = {},
            nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
        )
    }

    @Composable
    private fun RepoPreview(state: RepoUiState) {
        RepoScreen(
            state = state, signedIn = true, onBack = {}, onRefresh = {}, onSelectTab = {}, onRetryTab = {}, onToggleStar = {},
            onOpenDirectory = {}, onOpenParentDirectory = {}, onOpenFile = {}, onOpenIssue = {}, onOpenUser = {}, onLinkClick = {}, onOpenInBrowser = {},
            onErrorShown = {}, onStarFailureShown = {}, nowMillis = java.time.Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
        )
    }

    private val octocat = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))

    @Composable
    private fun SettingsPreview(settings: UserSettings = UserSettings()) {
        SettingsScreen(
            session = SessionState.SignedIn(octocat),
            onSignIn = {},
            onSignOut = {},
            settings = settings,
            versionName = "0.1.0",
            onThemeModeChange = {},
            onAmoledBlackChange = {},
            onOpenCredits = {},
            onOpenSourceCode = {},
            onBack = {},
        )
    }
}
