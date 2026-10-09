package fr.arthurbrugiere.forgeline.screenshots

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import fr.arthurbrugiere.forgeline.settings.SettingsSection
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import fr.arthurbrugiere.forgeline.navigation.rememberAppNavigator
import fr.arthurbrugiere.forgeline.ui.LocalOpenSearch
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.snapshots.Snapshot
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import androidx.compose.runtime.mutableStateOf
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
import androidx.compose.foundation.background
import fr.arthurbrugiere.forgeline.actions.DispatchContent
import fr.arthurbrugiere.forgeline.actions.DispatchUiState
import fr.arthurbrugiere.forgeline.actions.JobLogScreen
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.DispatchInputType
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.actions.JobLogUiState
import fr.arthurbrugiere.forgeline.actions.RunScreen
import fr.arthurbrugiere.forgeline.actions.RunUiState
import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.RunStep
import fr.arthurbrugiere.forgeline.core.testing.workflowRun
import fr.arthurbrugiere.forgeline.repo.CodeState
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.core.model.GitRefs
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
import fr.arthurbrugiere.forgeline.issue.IssueManageContent
import fr.arthurbrugiere.forgeline.issue.ManageActions
import fr.arthurbrugiere.forgeline.issue.ManagePage
import fr.arthurbrugiere.forgeline.issue.ManageUiState
import fr.arthurbrugiere.forgeline.issue.NewIssueScreen
import fr.arthurbrugiere.forgeline.issue.NewIssueUiState
import fr.arthurbrugiere.forgeline.issue.MARKDOWN_PENDING_TAG
import fr.arthurbrugiere.forgeline.issue.IssueUiState
import fr.arthurbrugiere.forgeline.user.UserScreen
import fr.arthurbrugiere.forgeline.user.UserTab
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

    private fun awaitHighlighted(code: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(code, substring = true), useUnmergedTree = true).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.spanStyles.isNotEmpty() }
            }
        }
    }

    private fun snapshot(
        name: String,
        darkTheme: Boolean,
        amoledBlack: Boolean = false,
        awaitText: String? = null,
        awaitTag: String? = null,
        awaitGoneTag: String? = null,
        beforeCapture: () -> Unit = {},
        // Sheets and dialogs live in their own window, which only a whole-screen capture includes.
        wholeScreen: Boolean = false,
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
        if (wholeScreen) {
            composeRule.waitForIdle()
            captureScreenRoboImage("src/test/screenshots/$name.png")
        } else {
            composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
        }
    }

    @Test
    fun app_shell_light() = snapshot("app_shell_light", darkTheme = false) { ForgelineApp(session = SessionState.SignedOut, onSignOut = {}, navigator = rememberAppNavigator(settled = true)) }

    @Test
    fun app_shell_dark() = snapshot("app_shell_dark", darkTheme = true) { ForgelineApp(session = SessionState.SignedOut, onSignOut = {}, navigator = rememberAppNavigator(settled = true)) }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun app_shell_font_2_0_light() = snapshot("app_shell_font_2_0_light", darkTheme = false) { ForgelineApp(session = SessionState.SignedOut, onSignOut = {}, navigator = rememberAppNavigator(settled = true)) }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun app_shell_tablet() = snapshot("app_shell_tablet", darkTheme = false) { ForgelineApp(session = SessionState.SignedOut, onSignOut = {}, navigator = rememberAppNavigator(settled = true)) }

    @Test
    fun settings_light() = snapshot("settings_light", darkTheme = false) { SettingsPreview() }

    @Test
    fun settings_dark_amoled() = snapshot("settings_dark_amoled", darkTheme = true, amoledBlack = true) {
        SettingsPreview(UserSettings(themeMode = ThemeMode.DARK, amoledBlack = true), section = SettingsSection.APPEARANCE)
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
    fun sign_in_own_gitlab_light() = snapshot("sign_in_own_gitlab_light", darkTheme = false) {
        SignInScreen(
            state = SignInUiState(
                deviceFlowAvailable = false, personalAccessTokenUrl = "https://gitlab.example.org/-/user_settings/personal_access_tokens",
                forge = fr.arthurbrugiere.forgeline.signin.SignInForge.GITLAB, gitlabOwnServer = true, host = "gitlab.example.org", forgeName = "gitlab.example.org",
                otherType = fr.arthurbrugiere.forgeline.core.model.ForgeType.GITLAB, oauthClientId = "a1b2c3d4e5f6", browserSignInAvailable = true,
            ),
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

    @Test
    fun you_limited_sign_in_dark() = snapshot("you_limited_sign_in_dark", darkTheme = true) {
        YouScreen(SessionState.SignedIn(octocat, limited = setOf(octocat.id)), onSignIn = {}, onOpenSettings = {})
    }

    @Test
    fun you_sign_in_ended_light() = snapshot("you_sign_in_ended_light", darkTheme = false) {
        val codeberg = Account(Account.idFor(ForgeInstance.Codeberg, "octocat"), ForgeInstance.Codeberg, ForgeUser("octocat", "The Octocat", null))
        YouScreen(SessionState.SignedIn(codeberg, listOf(octocat, codeberg), ended = setOf(codeberg.id)), onSignIn = {}, onOpenSettings = {})
    }

    @Test
    fun you_several_forges_light() = snapshot("you_several_forges_light", darkTheme = false) {
        val codeberg = Account(Account.idFor(ForgeInstance.Codeberg, "octocat"), ForgeInstance.Codeberg, ForgeUser("octocat", "The Octocat", null))
        val selfHosted = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
        val home = Account(Account.idFor(selfHosted, "octo"), selfHosted, ForgeUser("octo", null, null))
        YouScreen(SessionState.SignedIn(octocat, listOf(octocat, codeberg, home)), onSignIn = {}, onOpenSettings = {})
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
    fun trending_mixed_forges_light() = snapshot("trending_mixed_forges_light", darkTheme = false) {
        // GitHub and Codeberg on one page: every row wears its forge's logo, and one forge can be picked.
        val items = trendingState.items.mapIndexed { index, item ->
            if (index % 2 == 1) item.copy(repo = item.repo.copy(id = item.repo.id.copy(forge = ForgeInstance.Codeberg))) else item
        }
        TrendingPreview(trendingState.copy(items = items, showForge = true, forges = listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg)))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun trending_two_digit_ranks_font_2_0_light() = snapshot(
        "trending_two_digit_ranks_font_2_0_light",
        darkTheme = false,
        // Rank 10 and beyond, where a fixed-width rank column used to wrap its digits.
        beforeCapture = { composeRule.onNode(hasScrollAction()).performScrollToIndex(11) },
    ) {
        val items = (1..25).map { TrendingItem(trendingRepo("owner$it/project$it", stars = 1_000 * it, periodStars = 300 - it), null) }
        TrendingPreview(TrendingUiState(items = items, updatedAtMillis = 0L))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun repo_issues_font_2_0_light() = snapshot("repo_issues_font_2_0_light", darkTheme = false) {
        RepoPreview(
            repoState.copy(
                tab = RepoTab.ISSUES,
                issues = Loadable.Loaded(listOf(issueSummary(14127, "Heartbeat recovery escalates too early"), issueSummary(14121, "Wake-queue reopen activity record names"))),
            ),
        )
    }

    @Test
    fun repo_issues_pinned_light() = snapshot("repo_issues_pinned_light", darkTheme = false, beforeCapture = { composeRule.onAllNodes(hasScrollAction())[0].performScrollToIndex(1) }) {
        RepoPreview(
            repoState.copy(
                tab = RepoTab.ISSUES,
                pinned = listOf(issueSummary(12000, "Read this before reporting a bug")),
                issues = Loadable.Loaded(listOf(issueSummary(14127, "Heartbeat recovery escalates too early"), issueSummary(14121, "Wake-queue reopen activity record names"))),
            ),
        )
    }

    @Test
    fun repo_issues_closed_search_dark() = snapshot("repo_issues_closed_search_dark", darkTheme = true, beforeCapture = { composeRule.onAllNodes(hasScrollAction())[0].performScrollToIndex(1) }) {
        RepoPreview(
            repoState.copy(
                tab = RepoTab.ISSUES,
                issueQuery = fr.arthurbrugiere.forgeline.core.model.IssueQuery(open = false, text = "proxy"),
                issues = Loadable.Loaded(listOf(issueSummary(13990, "Installer ignores the proxy setting", state = fr.arthurbrugiere.forgeline.core.model.IssueState.CLOSED))),
            ),
        )
    }

    @Test
    fun repo_pulls_closed_light() = snapshot("repo_pulls_closed_light", darkTheme = false, beforeCapture = { composeRule.onAllNodes(hasScrollAction())[0].performScrollToIndex(1) }) {
        RepoPreview(
            repoState.copy(
                tab = RepoTab.PULLS,
                pullQuery = fr.arthurbrugiere.forgeline.core.model.IssueQuery(open = false),
                pulls = Loadable.Loaded(
                    listOf(
                        issueSummary(14129, "Keep install flags on retry", isPullRequest = true, state = fr.arthurbrugiere.forgeline.core.model.IssueState.MERGED),
                        issueSummary(14101, "Try a faster heartbeat", isPullRequest = true, state = fr.arthurbrugiere.forgeline.core.model.IssueState.CLOSED),
                    ),
                ),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun repo_issues_tablet_light() = snapshot("repo_issues_tablet_light", darkTheme = false) {
        RepoPreview(
            repoState.copy(
                tab = RepoTab.ISSUES,
                issues = Loadable.Loaded(listOf(issueSummary(14127, "Heartbeat recovery escalates too early"), issueSummary(14121, "Wake-queue reopen activity record names"))),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun app_shell_landscape_phone_light() = snapshot("app_shell_landscape_phone_light", darkTheme = false) {
        ForgelineApp(session = SessionState.SignedOut, onSignOut = {}, navigator = rememberAppNavigator(settled = true))
    }

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
    fun repo_readme_light() = snapshot(
        "repo_readme_light",
        darkTheme = false,
        awaitText = "teams of AI agents",
        // Code blocks are highlighted off the main thread: wait until the code carries its colors.
        beforeCapture = { awaitHighlighted("npx paperclip init") },
    ) {
        RepoPreview(repoState.copy(watching = true))
    }

    @Test
    fun repo_ref_sheet_dark() = snapshot(
        "repo_ref_sheet_dark",
        darkTheme = true,
        wholeScreen = true,
        beforeCapture = {
            composeRule.onNode(hasContentDescription("Browsing master. Switch branch or tag")).performClick()
            composeRule.mainClock.advanceTimeBy(1_000)
        },
    ) {
        RepoPreview(
            repoState.copy(
                tab = RepoTab.CODE,
                code = CodeState("", Loadable.Loaded(emptyList())),
                refs = Loadable.Loaded(
                    GitRefs(
                        branches = listOf("LOOA-700-recovery-tightloop", "master", "PAP-10015-per-use-join-leave-projects-and-agents", "release/2026.9"),
                        tags = listOf("v2026.916.1", "v2026.916.0"),
                    ),
                ),
            ),
        )
    }

    private val runStart = java.time.Instant.parse("2026-09-29T07:57:44Z")

    @Test
    fun run_failed_light() = snapshot("run_failed_light", darkTheme = false) {
        RunScreen(
            state = RunUiState(
                repo = RepoId("paperclipai", "paperclip"),
                runId = 36539745670,
                run = workflowRun(36539745670, title = "fix: keep the blocked inbox reason and action", conclusion = RunConclusion.FAILURE)
                    .copy(workflowName = "PR", branch = "fix/blocked-inbox-reason-and-action", event = "pull_request", runNumber = 39953, attempt = 2),
                jobs = listOf(
                    RunJob(1, "ci / Select trusted runner", RunStatus.COMPLETED, RunConclusion.SUCCESS, runStart, runStart.plusSeconds(4), emptyList()),
                    RunJob(
                        2, "ci / Verify Paperclip Runner (vitest 1/2)", RunStatus.COMPLETED, RunConclusion.FAILURE, runStart, runStart.plusSeconds(361),
                        listOf(RunStep(10, "Verify Paperclip Runner", RunStatus.COMPLETED, RunConclusion.FAILURE)),
                    ),
                    RunJob(3, "ci / e2e shard (5/8)", RunStatus.IN_PROGRESS, null, runStart, null, emptyList()),
                    RunJob(4, "ci / verify", RunStatus.QUEUED, null, null, null, emptyList()),
                ),
            ),
            signedIn = true, onBack = {}, onRefresh = {}, onPerform = {}, onOpenJob = {}, onOpenUser = {}, onOpenInBrowser = {},
            onResultShown = {}, onErrorShown = {}, nowMillis = runStart.plusSeconds(400).toEpochMilli(),
        )
    }

    @Test
    fun dispatch_form_light() = snapshot("dispatch_form_light", darkTheme = false) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(fr.arthurbrugiere.forgeline.core.ui.soft.Soft.colors.ground)) {
            DispatchContent(
                state = DispatchUiState(
                    selected = Workflow(1, "Release", ".github/workflows/release.yml"),
                    ref = "master",
                    inputs = Loadable.Loaded(
                        listOf(
                            DispatchInput("channel", "Release channel to publish", DispatchInputType.CHOICE, true, "stable", listOf("stable", "beta", "nightly", "preview")),
                            DispatchInput("source_ref", "Stable source ref, or full immutable SHA for a preview build", DispatchInputType.STRING, true, "master", emptyList()),
                            DispatchInput("dry_run", "Build everything but publish nothing", DispatchInputType.BOOLEAN, false, "false", emptyList()),
                        ),
                    ),
                    values = mapOf("channel" to "beta", "source_ref" to "master", "dry_run" to "true"),
                ),
                onSelect = {}, onRefChange = {}, onRefDone = {}, onValueChange = { _, _ -> }, onStart = {}, onRetry = {},
            )
        }
    }

    @Test
    fun job_log_dark() = snapshot("job_log_dark", darkTheme = true) {
        JobLogScreen(
            state = JobLogUiState(
                repo = RepoId("paperclipai", "paperclip"),
                jobId = 2,
                jobName = "ci / Verify Paperclip Runner (vitest 1/2)",
                log = Loadable.Loaded(
                    JobLog(
                        listOf(
                            LogEntry.Line("Current runner version: '2.337.0'"),
                            LogEntry.Group("Runner Image Provisioner", listOf(LogEntry.Line("Hosted Compute Agent"))),
                            LogEntry.Group("Operating System", listOf(LogEntry.Line("Ubuntu 24.04"))),
                            LogEntry.Group(
                                "Run pnpm --filter @paperclipai/paperclip-runner test:typescript:vitest --shard=1/2",
                                listOf(
                                    LogEntry.Line("pnpm --filter @paperclipai/paperclip-runner test:typescript:vitest --shard=1/2", LogLineKind.COMMAND),
                                    LogEntry.Line("\u001B[2m Test Files \u001B[22m \u001B[1m\u001B[31m1 failed\u001B[39m\u001B[22m\u001B[2m | \u001B[22m\u001B[1m\u001B[32m76 passed\u001B[39m\u001B[22m\u001B[2m | \u001B[22m\u001B[33m1 skipped\u001B[39m"),
                                    LogEntry.Line("\u001B[2m   Duration \u001B[22m 191.00s"),
                                    LogEntry.Line("NativeSessionCloseUnrecoverableError: provider_transport_failed: runner did not durably suspend before checkpoint", LogLineKind.ERROR),
                                    LogEntry.Line(" ❯ DurablePrpCodexTransport.#closeOnce src/live/runnerd-codex-transport.ts:4314:13", LogLineKind.ERROR),
                                    LogEntry.Line("Process completed with exit code 1.", LogLineKind.ERROR),
                                ),
                            ),
                            LogEntry.Line("Post job cleanup."),
                            LogEntry.Line("/usr/bin/git version", LogLineKind.COMMAND),
                            LogEntry.Line("git version 2.55.0"),
                        ),
                    ),
                ),
                openGroups = setOf(3),
            ),
            onBack = {}, onRetry = {}, onToggleGroup = {}, onSignIn = {}, onOpenInBrowser = {},
        )
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
            canComment = true, onDraftChange = {}, onSendComment = {}, onToggleOpen = {}, onSignIn = {}, onCommentNoticeShown = {},
            onBack = {}, onRefresh = {}, onLoadMore = {}, onOpenIssue = {}, onOpenRepo = {}, onOpenUser = {}, onOpenInBrowser = {},
            onLinkClick = {}, onErrorShown = {}, nowMillis = java.time.Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
        )
    }

    /** Where the long conversation below is sent once it is on screen. */
    private val jump = androidx.compose.runtime.mutableStateOf<fr.arthurbrugiere.forgeline.issue.ScrollTarget?>(null)

    /** In the middle of a long conversation: both ends are one tap away. */
    @Composable
    private fun LongIssue() {
        val ref = IssueRef(RepoId("paperclipai", "paperclip"), 14127)
        IssueScreen(
            state = IssueUiState(
                ref = ref,
                issue = issueDetails(ref, "Heartbeat recovery escalates too early"),
                items = (1..30L).map { comment(it, "Remark $it: it happens after every restart.", login = "hubot") },
                scrollTo = jump.value,
            ),
            canComment = true, onDraftChange = {}, onSendComment = {}, onToggleOpen = {}, onSignIn = {}, onCommentNoticeShown = {},
            onBack = {}, onRefresh = {}, onLoadMore = {}, onOpenIssue = {}, onOpenRepo = {}, onOpenUser = {}, onOpenInBrowser = {},
            onLinkClick = {}, onErrorShown = {}, nowMillis = java.time.Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
        )
    }

    /**
     * Sends the conversation to its 15th remark and lets the list settle there. Time passes frame by frame and no
     * faster than a real clock, the main thread taking what other threads have finished in between, as on a device:
     * a test clock that runs ahead is done holding the list in place before the comments have been laid out.
     */
    private fun jumpAndSettle() {
        composeRule.mainClock.autoAdvance = false
        jump.value = fr.arthurbrugiere.forgeline.issue.ScrollTarget.Item(14)
        repeat(125) {
            composeRule.mainClock.advanceTimeByFrame()
            Thread.sleep(16)
            composeRule.waitForIdle()
        }
    }

    @Test
    fun issue_jump_light() = snapshot("issue_jump_light", darkTheme = false, awaitText = "Remark 1:", awaitGoneTag = MARKDOWN_PENDING_TAG, beforeCapture = ::jumpAndSettle) { LongIssue() }

    @Test
    fun issue_jump_dark() = snapshot("issue_jump_dark", darkTheme = true, awaitText = "Remark 1:", awaitGoneTag = MARKDOWN_PENDING_TAG, beforeCapture = ::jumpAndSettle) { LongIssue() }

    /** A short conversation, so the comment box that closes it is on screen. */
    @Composable
    private fun IssueWithComposer(state: (IssueUiState) -> IssueUiState, canComment: Boolean = true, suggestions: fr.arthurbrugiere.forgeline.issue.ReferenceOffer = fr.arthurbrugiere.forgeline.issue.ReferenceOffer()) {
        val ref = IssueRef(RepoId("paperclipai", "paperclip"), 14127)
        IssueScreen(
            state = state(
                IssueUiState(
                    ref = ref,
                    issue = issueDetails(ref, "Heartbeat recovery escalates too early").copy(body = "It pages after one missed beat."),
                    items = listOf(comment(1, "Same here on 2026.9.", login = "hubot")),
                ),
            ),
            canComment = canComment, onDraftChange = {}, onSendComment = {}, onToggleOpen = {}, onSignIn = {}, onCommentNoticeShown = {},
            onBack = {}, onRefresh = {}, onLoadMore = {}, onOpenIssue = {}, onOpenRepo = {}, onOpenUser = {}, onOpenInBrowser = {},
            onLinkClick = {}, onErrorShown = {}, nowMillis = java.time.Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
            suggestions = suggestions,
        )
    }

    @Test
    fun issue_reference_suggestions_light() = snapshot("issue_reference_suggestions_light", darkTheme = false, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        IssueWithComposer(
            { it.copy(items = listOf(comment(1, "Same here on 2026.9, like #14120.", login = "hubot")), draft = "Fixed by #inst") },
            // The recent ones that match show while the forge is searched.
            suggestions = fr.arthurbrugiere.forgeline.issue.ReferenceOffer(
                listOf(issueSummary(14129, "Keep install flags on retry", isPullRequest = true), issueSummary(13990, "Installer ignores the proxy setting")),
                isLoading = true,
            ),
        )
    }

    @Test
    fun issue_comment_empty_light() = snapshot("issue_comment_empty_light", darkTheme = false, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        IssueWithComposer({ it })
    }

    @Test
    fun issue_comment_written_dark() = snapshot("issue_comment_written_dark", darkTheme = true, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        IssueWithComposer({ it.copy(draft = "Confirmed on 2026.10 too. The threshold in `heartbeat.yml` is read as seconds, not beats.") })
    }

    @Test
    fun issue_comment_refused_light() = snapshot("issue_comment_refused_light", darkTheme = false, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        IssueWithComposer({ it.copy(draft = "Confirmed on 2026.10 too.", commentError = fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http(403, "locked")) })
    }

    @Test
    fun issue_comment_rewrite_light() = snapshot("issue_comment_rewrite_light", darkTheme = false, awaitText = "Rewriting your comment", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        IssueWithComposer({ it.copy(me = "hubot", editing = 1, draft = "Same here on 2026.10, it happens after every restart.", canChangeState = true) })
    }

    @Test
    fun issue_comment_preview_dark() = snapshot(
        "issue_comment_preview_dark", darkTheme = true, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG,
        beforeCapture = {
            composeRule.onNodeWithContentDescription("Preview").performClick()
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("not minutes", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasTestTag(MARKDOWN_PENDING_TAG), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        },
    ) {
        IssueWithComposer({ it.copy(draft = "Confirmed on **2026.10** too. The threshold in `heartbeat.yml` is read as seconds:\n\n- not beats\n- not minutes") })
    }

    @Test
    fun issue_comment_menu_light() = snapshot(
        "issue_comment_menu_light", darkTheme = false, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG, wholeScreen = true,
        beforeCapture = { composeRule.onAllNodesWithContentDescription("Comment options")[1].performClick() },
    ) {
        IssueWithComposer({ it.copy(me = "hubot") })
    }

    @Test
    fun issue_close_light() = snapshot("issue_close_light", darkTheme = false, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        IssueWithComposer({ it.copy(canChangeState = true, draft = "Fixed in 2026.10.") })
    }

    @Test
    fun issue_reopen_refused_dark() = snapshot("issue_reopen_refused_dark", darkTheme = true, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        IssueWithComposer({
            it.copy(
                issue = it.issue?.copy(state = fr.arthurbrugiere.forgeline.core.model.IssueState.CLOSED),
                canChangeState = true,
                stateError = fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http(403, "no"),
            )
        })
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun issue_close_font_2_0_light() = snapshot(
        "issue_close_font_2_0_light", darkTheme = false, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG,
        // The two actions no longer fit side by side: scroll to where they stack.
        beforeCapture = {
            composeRule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("Comment"))
        },
    ) {
        IssueWithComposer({ it.copy(canChangeState = true) })
    }

    @Test
    fun issue_locked_light() = snapshot("issue_locked_light", darkTheme = false, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        val maintainer = ForgeUser("maintainer", null, null)
        val at = java.time.Instant.parse("2026-09-26T09:30:00Z")
        IssueWithComposer({
            it.copy(
                issue = it.issue?.copy(
                    state = fr.arthurbrugiere.forgeline.core.model.IssueState.CLOSED, isLocked = true,
                    assignees = listOf(maintainer), milestone = fr.arthurbrugiere.forgeline.core.model.Milestone(4, "2026.10"),
                ),
                items = it.items + listOf(
                    TimelineItem.Event(fr.arthurbrugiere.forgeline.core.model.ConversationEvent.ASSIGNED, maintainer, "maintainer", at),
                    TimelineItem.Event(fr.arthurbrugiere.forgeline.core.model.ConversationEvent.LOCKED, maintainer, null, at),
                    TimelineItem.Event(fr.arthurbrugiere.forgeline.core.model.ConversationEvent.CONVERTED_TO_DISCUSSION, maintainer, null, at),
                ),
            )
        })
    }

    @Composable
    private fun ManageSheetPreview(page: ManagePage, change: (IssueUiState) -> IssueUiState = { it }) {
        val ref = IssueRef(RepoId("paperclipai", "paperclip"), 14127)
        val labels = listOf(Label("bug", "d73a4a"), Label("enhancement", "a2eeef"), Label("needs triage", "fbca04"), Label("question", "d876e3"))
        val people = listOf(ForgeUser("cryppadotta", null, null), ForgeUser("devinfoley", null, null))
        val milestone = fr.arthurbrugiere.forgeline.core.model.Milestone(4, "2026.10")
        val state = IssueUiState(
            ref,
            issueDetails(ref, "Heartbeat recovery escalates too early").copy(labels = labels.take(1), assignees = people.take(1), milestone = milestone),
            access = fr.arthurbrugiere.forgeline.core.model.RepoAccess.ADMIN, canChangeState = true,
            supported = fr.arthurbrugiere.forgeline.core.model.ConversationAction.entries.toSet(),
            manage = ManageUiState(Loadable.Loaded(labels), Loadable.Loaded(people), Loadable.Loaded(listOf(milestone)), pinned = false),
        )
        // The sheet's own ground, without the sheet: its content is what is drawn here.
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(fr.arthurbrugiere.forgeline.core.ui.soft.Soft.colors.ground).padding(top = androidx.compose.ui.unit.Dp(24f)),
        ) {
            IssueManageContent(change(state), ManageActions(), onDismiss = {}, startPage = page, nowMillis = java.time.Instant.parse("2026-10-02T10:00:00Z").toEpochMilli())
        }
    }

    // The calendar rings today's date: a month far from now keeps these pictures the same from one day to the next.
    private val farDueDate = java.time.LocalDate.parse("2031-03-14")

    @Test
    fun issue_manage_due_date_light() = snapshot("issue_manage_due_date_light", darkTheme = false) {
        ManageSheetPreview(ManagePage.DUE_DATE) { it.copy(issue = it.issue?.copy(dueDate = farDueDate)) }
    }

    @Test
    fun issue_manage_due_date_dark() = snapshot("issue_manage_due_date_dark", darkTheme = true) {
        ManageSheetPreview(ManagePage.DUE_DATE) { it.copy(issue = it.issue?.copy(dueDate = farDueDate)) }
    }

    @Test
    fun issue_manage_time_light() = snapshot("issue_manage_time_light", darkTheme = false) {
        ManageSheetPreview(ManagePage.TIME) {
            it.copy(
                manage = it.manage.copy(
                    tracking = Loadable.Loaded(fr.arthurbrugiere.forgeline.core.model.TimeTracking(9_000, java.time.Instant.parse("2026-10-02T09:20:00Z"))),
                ),
            )
        }
    }

    @Test
    fun issue_manage_dependencies_dark() = snapshot("issue_manage_dependencies_dark", darkTheme = true) {
        ManageSheetPreview(ManagePage.DEPENDENCIES) {
            val repo = it.ref.repo
            it.copy(
                manage = it.manage.copy(
                    dependencies = Loadable.Loaded(
                        listOf(
                            fr.arthurbrugiere.forgeline.core.model.LinkedIssue(IssueRef(repo, 13990), "Installer ignores the proxy setting", fr.arthurbrugiere.forgeline.core.model.IssueState.CLOSED),
                            fr.arthurbrugiere.forgeline.core.model.LinkedIssue(IssueRef(RepoId("paperclipai", "docs"), 212), "Document the heartbeat thresholds", fr.arthurbrugiere.forgeline.core.model.IssueState.OPEN),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun issue_manage_menu_light() = snapshot("issue_manage_menu_light", darkTheme = false) { ManageSheetPreview(ManagePage.MENU) }

    @Test
    fun issue_manage_menu_dark() = snapshot("issue_manage_menu_dark", darkTheme = true) { ManageSheetPreview(ManagePage.MENU) }

    @Test
    fun issue_manage_labels_dark() = snapshot("issue_manage_labels_dark", darkTheme = true) { ManageSheetPreview(ManagePage.LABELS) }

    @Test
    fun issue_manage_assignees_light() = snapshot("issue_manage_assignees_light", darkTheme = false) { ManageSheetPreview(ManagePage.ASSIGNEES) }

    @Test
    fun issue_manage_delete_refused_light() = snapshot("issue_manage_delete_refused_light", darkTheme = false) {
        ManageSheetPreview(ManagePage.DELETE) { it.copy(manage = it.manage.copy(error = fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http(403, "no"))) }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun issue_manage_menu_font_2_0_light() = snapshot("issue_manage_menu_font_2_0_light", darkTheme = false) { ManageSheetPreview(ManagePage.MENU) }

    @Test
    fun issue_comment_signed_out_light() = snapshot("issue_comment_signed_out_light", darkTheme = false, awaitText = "Same here on 2026.9", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        IssueWithComposer({ it }, canComment = false)
    }

    @Composable
    private fun NewIssue(state: (NewIssueUiState) -> NewIssueUiState, signedIn: Boolean = true) {
        NewIssueScreen(
            state = state(NewIssueUiState(RepoId("paperclipai", "paperclip"))), signedIn = signedIn,
            onTitleChange = {}, onBodyChange = {}, onSend = {}, onSignIn = {}, onBack = {},
        )
    }

    @Test
    fun new_issue_empty_light() = snapshot("new_issue_empty_light", darkTheme = false) { NewIssue({ it }) }

    @Test
    fun new_issue_written_dark() = snapshot("new_issue_written_dark", darkTheme = true) {
        NewIssue({
            it.copy(
                title = "Heartbeat recovery escalates too early",
                body = "It pages after one missed beat.\n\nSteps:\n1. Stop the runner\n2. Wait 30 seconds\n\nExpected: three missed beats before paging.",
            )
        })
    }

    @Test
    fun new_issue_refused_light() = snapshot("new_issue_refused_light", darkTheme = false) {
        NewIssue({ it.copy(title = "Heartbeat recovery escalates too early", body = "It pages after one missed beat.", error = fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http(410, "disabled")) })
    }

    @Test
    fun new_issue_signed_out_light() = snapshot("new_issue_signed_out_light", darkTheme = false) { NewIssue({ it }, signedIn = false) }

    private val shownRelease = fr.arthurbrugiere.forgeline.core.model.Release(
        tag = "v2026.916.1",
        name = "September: heartbeats that recover on their own",
        body = "## What's new\n\n- Heartbeat recovery waits three beats before paging\n- The installer honours the proxy setting\n\nSee the [upgrade guide](docs/UPGRADE.md).",
        // The same time of day as the "now" these pictures are taken at: "4 days ago" counts calendar days, which a
        // release published late in the evening would make 4 in one time zone and 5 in another.
        publishedAt = java.time.Instant.parse("2026-09-22T10:00:00Z"),
        isPrerelease = false,
        author = ForgeUser("cryppadotta", null, null),
        assets = listOf(
            fr.arthurbrugiere.forgeline.core.model.ReleaseAsset("paperclip-2026.916.1-linux-amd64.tar.gz", 122_204_040, 4_321, "https://example.org/a"),
            fr.arthurbrugiere.forgeline.core.model.ReleaseAsset("paperclip-2026.916.1.apk", 6_081_740, 1_532, "https://example.org/b"),
            fr.arthurbrugiere.forgeline.core.model.ReleaseAsset("checksums.txt", 512, 87, "https://example.org/c"),
        ),
        zipUrl = "https://example.org/zip", tarUrl = "https://example.org/tar",
        reactions = mapOf(Reaction.HOORAY to 12, Reaction.ROCKET to 3),
        isLatest = true,
    )

    @Composable
    private fun ReleasePreview(release: fr.arthurbrugiere.forgeline.core.model.Release = shownRelease) {
        fr.arthurbrugiere.forgeline.release.ReleaseScreen(
            state = fr.arthurbrugiere.forgeline.release.ReleaseUiState(RepoId("paperclipai", "paperclip"), release.tag, release),
            onBack = {}, onRefresh = {}, onOpenRepo = {}, onOpenUser = {}, onDownload = {}, onOpenInBrowser = {}, onLinkClick = {}, onErrorShown = {},
            nowMillis = java.time.Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
        )
    }

    @Test
    fun release_light() = snapshot("release_light", darkTheme = false, awaitText = "Heartbeat recovery waits") { ReleasePreview() }

    @Test
    fun release_dark() = snapshot("release_dark", darkTheme = true, awaitText = "Heartbeat recovery waits") { ReleasePreview() }

    @Test
    fun release_prerelease_bare_light() = snapshot("release_prerelease_bare_light", darkTheme = false) {
        ReleasePreview(fr.arthurbrugiere.forgeline.core.model.Release("v2026.1001.0-rc.1", null, null, java.time.Instant.parse("2026-09-25T10:00:00Z"), true, null))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun release_font_2_0_light() = snapshot("release_font_2_0_light", darkTheme = false, awaitText = "Heartbeat recovery waits") { ReleasePreview() }

    @Test
    fun repo_releases_light() = snapshot("repo_releases_light", darkTheme = false, beforeCapture = { composeRule.onAllNodes(hasScrollAction())[0].performScrollToIndex(1) }) {
        RepoPreview(
            repoState.copy(
                tab = RepoTab.RELEASES,
                releases = Loadable.Loaded(
                    listOf(
                        fr.arthurbrugiere.forgeline.core.model.Release("v2026.1001.0-rc.1", null, null, java.time.Instant.parse("2026-09-25T10:00:00Z"), true, null),
                        shownRelease,
                        fr.arthurbrugiere.forgeline.core.model.Release("v2026.831.1", "August", null, java.time.Instant.parse("2026-08-31T10:00:00Z"), false, null),
                    ),
                ),
            ),
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

    @Test
    fun user_starred_light() = snapshot("user_starred_light", darkTheme = false) {
        UserScreen(
            state = UserUiState(
                login = "octocat",
                profile = userProfile("octocat", name = "The Octocat"),
                tab = UserTab.STARRED,
                starred = Loadable.Loaded(
                    listOf(
                        RepoSummary(RepoId("paperclipai", "paperclip"), "Open-source orchestration for teams of AI agents.", "TypeScript", 85_900, 4_200, false, null),
                        RepoSummary(RepoId("tokio-rs", "tokio-console"), "A debugger for async Rust programs.", "Rust", 3_800, 164, false, null),
                        RepoSummary(RepoId("mitchellh", "ghostty-themes"), "Color themes for Ghostty.", "Go", 1_200, 40, false, null),
                    ),
                ),
                following = true,
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
                    "41", repo = "acme/rocket", title = "Nightly build", type = SubjectType.CHECK_SUITE, number = null,
                    reason = NotificationReason.CI_ACTIVITY, unread = false, updatedAt = "2026-09-26T20:00:00Z",
                ),
                notificationThread(
                    "7", repo = "acme/satellite", title = "Orbit maths drift after a week", reason = NotificationReason.SUBSCRIBED,
                    unread = false, updatedAt = "2026-09-26T19:00:00Z",
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
    fun inbox_several_accounts_light() = snapshot("inbox_several_accounts_light", darkTheme = false) { InboxAccountsPreview() }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun inbox_several_accounts_font_2_0_light() = snapshot("inbox_several_accounts_font_2_0_light", darkTheme = false) { InboxAccountsPreview() }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun inbox_several_accounts_tablet_light() = snapshot("inbox_several_accounts_tablet_light", darkTheme = false) { InboxAccountsPreview() }

    @Composable
    private fun InboxAccountsPreview() {
        val codeberg = Account(Account.idFor(ForgeInstance.Codeberg, "octocat"), ForgeInstance.Codeberg, ForgeUser("octocat", "The Octocat", null))
        val onCodeberg = notificationThread("51", repo = "forgejo/forgejo", title = "Runner ignores the label filter", reason = NotificationReason.REVIEW_REQUESTED, updatedAt = "2026-09-27T09:20:00Z")
            .let { it.copy(repo = it.repo.copy(forge = ForgeInstance.Codeberg), accountId = codeberg.id) }
        val onGitHub = notificationThread("42", repo = "acme/rocket", title = "Launch fails on cold start", updatedAt = "2026-09-27T09:30:00Z")
        val octocatOnGitHub = Account(Account.idFor(ForgeInstance.GitHub, "octocat"), ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))
        InboxScreen(
            state = InboxUiState(
                filter = InboxFilter.ALL,
                groups = listOf(SectionGroup(InboxSection.NEEDS_YOU, listOf(onGitHub, onCodeberg))),
                accountTabs = listOf(octocatOnGitHub, codeberg),
                showForge = true,
                syncedAtMillis = 1,
            ),
            onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {}, onMarkDone = {}, onUnsubscribe = {},
            onErrorShown = {}, onActionFailureShown = {}, nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
        )
    }

    @Test
    fun inbox_everything_else_mixed_forges_light() = snapshot("inbox_everything_else_mixed_forges_light", darkTheme = false) {
        fun on(forge: ForgeInstance, id: String, repo: String, title: String) =
            notificationThread(id, repo = repo, title = title, reason = NotificationReason.SUBSCRIBED, updatedAt = "2026-09-27T09:3${id}:00Z")
                .let { it.copy(repo = it.repo.copy(forge = forge)) }
        InboxScreen(
            state = InboxUiState(
                filter = InboxFilter.ALL,
                groups = listOf(
                    SectionGroup(
                        InboxSection.OTHERS,
                        listOf(
                            on(ForgeInstance.Codeberg, "1", "forgejo/forgejo", "Runner ignores the label filter"),
                            on(ForgeInstance.GitHub, "2", "acme/rocket", "Cold start docs are out of date"),
                            on(ForgeInstance.GitHub, "3", "acme/launchpad", "Retry the fuel pump"),
                        ),
                    ),
                ),
                showForge = true,
                syncedAtMillis = 1,
            ),
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
                undo = PendingUndo(notificationThread("45").key, InboxAction.DONE, serial = 1),
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

    @Test
    fun feed_mixed_forges_light() = snapshot("feed_mixed_forges_light", darkTheme = false) { FeedMixedPreview() }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun feed_font_2_0_light() = snapshot("feed_font_2_0_light", darkTheme = false) { FeedPreview() }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun feed_tablet_light() = snapshot("feed_tablet_light", darkTheme = false) { FeedPreview() }

    @Test
    fun feed_starred_repositories_light() = snapshot("feed_starred_repositories_light", darkTheme = false) {
        // What starred repositories publish: a pre-release, a stable release with a name, and an announcement.
        val events = listOf(
            feedEvent("s1", actor = "alextran", repo = "immich-app/immich", createdAt = "2026-09-27T09:50:00Z", action = FeedAction.Released("v3.3.0-rc.1", null, prerelease = true)),
            feedEvent("s2", actor = "netbirdio", repo = "netbirdio/netbird", createdAt = "2026-09-27T09:30:00Z", action = FeedAction.Released("v0.80.0", "Faster peer sync", prerelease = false)),
            feedEvent("s3", actor = "alextran", repo = "immich-app/immich", createdAt = "2026-09-27T08:00:00Z", action = FeedAction.Announced(880, "Immich turns three")),
        )
        FeedScreen(
            state = FeedUiState(items = feedItems(events, FeedKind.defaults), syncedAtMillis = 1),
            onRefresh = {}, onLoadMore = {}, onOpenRepo = {}, onOpenIssue = {}, onOpenUser = { _, _ -> }, onErrorShown = {},
            nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
            zone = java.time.ZoneOffset.UTC,
        )
    }

    @Composable
    private fun FeedMixedPreview() {
        val onCodeberg = feedEvent("21", actor = "alice", repo = "forgejo/forgejo", createdAt = "2026-09-27T09:50:00Z", action = FeedAction.PullRequest(PullRequestAction.MERGED, 43))
            .let { it.copy(repo = it.repo.copy(forge = ForgeInstance.Codeberg)) }
        val onGitHub = feedEvent("20", actor = "bob", repo = "acme/rocket", createdAt = "2026-09-27T09:45:00Z", action = FeedAction.Issue(IssueAction.CLOSED, 42, "Launch fails on cold start"))
        val onSelfHosted = feedEvent("19", actor = "carol", repo = "octo/tools", createdAt = "2026-09-27T09:40:00Z", action = FeedAction.Released("v2.0.0", "Tools 2.0: faster everything", prerelease = false))
            .let { it.copy(repo = it.repo.copy(forge = ForgeInstance(ForgeType.FORGEJO, "git.example.org"))) }
        // On gitlab.com, in a nested group as its projects often are, and what a real account's Feed is made of: a push.
        val onGitLab = feedEvent("18", actor = "dana", repo = "x/y", createdAt = "2026-09-27T09:35:00Z", action = FeedAction.Commented(7, "Keep install flags on retry", isPullRequest = true))
            .let { it.copy(repo = RepoId("gitlab-org/ci-cd", "runner-tools", ForgeInstance.GitLab)) }
        val pushedOnGitLab = feedEvent("17", actor = "dana", repo = "x/y", createdAt = "2026-09-27T09:30:00Z", action = FeedAction.Pushed("main"))
            .let { it.copy(repo = RepoId("gitlab-org/ci-cd/release-tooling/pipelines", "runner-tools-and-helpers", ForgeInstance.GitLab)) }
        // As seen on a phone: an owner and a name, long enough to take the line by themselves.
        val longOnGitLab = feedEvent("16", actor = "erin", repo = "x/y", createdAt = "2026-09-27T09:25:00Z", action = FeedAction.Issue(IssueAction.OPENED, 1, "Heartbeat recovery escalates too early"))
            .let { it.copy(repo = RepoId("RoiArthurB", "forgeline-scratch-deletion_scheduled-87209551", ForgeInstance.GitLab)) }
        val longOnGitHub = feedEvent("15", actor = "frank", repo = "project-SIMPLE/simple.BeDev.AEDES.UnityVR", createdAt = "2026-09-27T09:20:00Z", action = FeedAction.PullRequest(PullRequestAction.OPENED, 58, "Fix the headset pairing"))
        FeedScreen(
            state = FeedUiState(items = feedItems(listOf(onCodeberg, onGitHub, onSelfHosted, onGitLab, pushedOnGitLab, longOnGitLab, longOnGitHub), fr.arthurbrugiere.forgeline.core.model.FeedKind.entries.toSet()), showForge = true, syncedAtMillis = 1),
            onRefresh = {}, onLoadMore = {}, onOpenRepo = {}, onOpenIssue = {}, onOpenUser = { _, _ -> }, onErrorShown = {},
            nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
            zone = java.time.ZoneOffset.UTC,
        )
    }

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
            onRefresh = {}, onLoadMore = {}, onOpenRepo = {}, onOpenIssue = {}, onOpenUser = { _, _ -> }, onErrorShown = {},
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
            onOpenRepo = {}, onOpenIssue = {}, onOpenUser = { _, _ -> }, onBack = {},
            nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
        )
    }

    @Test
    fun search_mixed_forges_light() = snapshot("search_mixed_forges_light", darkTheme = false) { SearchMixedPreview() }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun search_mixed_forges_tablet_light() = snapshot("search_mixed_forges_tablet_light", darkTheme = false) { SearchMixedPreview() }

    @Composable
    private fun SearchMixedPreview() {
        // Several forges searched: every result wears its forge's logo; a self-hosted server adds its host.
        val codeberg = ForgeInstance.Codeberg
        val selfHosted = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
        SearchScreen(
            state = SearchUiState(
                query = "zig",
                scope = SearchScope.REPOSITORIES,
                results = ScopeResults(
                    query = "zig",
                    items = listOf(
                        SearchResult.Repository(repoSummary("ziglang/zig", stars = 42_000, description = "General-purpose programming language")),
                        SearchResult.Repository(repoSummary("ziglang/zig", stars = 6_712, description = "General-purpose programming language", forge = codeberg)),
                        SearchResult.Repository(repoSummary("team/zig-tools", stars = 12, description = "Helpers for our Zig builds", forge = selfHosted)),
                        SearchResult.Issue(IssueSearchResult(RepoId("ziglang", "zig", codeberg), issueSummary(21, "Linker crash on arm64"))),
                        SearchResult.User(UserSummary("andrewrk", null, isOrganization = false, forge = codeberg)),
                    ),
                    totalCount = 5,
                    forges = listOf(ForgeInstance.GitHub, codeberg, selfHosted),
                ),
                forges = listOf(ForgeInstance.GitHub, codeberg, selfHosted),
            ),
            onQueryChange = {}, onSubmit = {}, onSelectScope = {}, onLoadMore = {}, onRetry = {},
            onOpenRepo = {}, onOpenIssue = {}, onOpenUser = { _, _ -> }, onBack = {},
            nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
        )
    }

    @Composable
    private fun RepoPreview(state: RepoUiState) {
        RepoScreen(
            state = state, signedIn = true, onBack = {}, onRefresh = {}, onSelectTab = {}, onRetryTab = {}, onToggleStar = {},
            onOpenDirectory = {}, onOpenParentDirectory = {}, onOpenFile = {}, onOpenIssue = { _, _ -> }, onNewIssue = {}, onOpenUser = {}, onLinkClick = {}, onOpenRun = {}, onOpenInBrowser = {}, onLoadRefs = {}, onSelectRef = {},
            onRunWorkflow = {}, onWorkflowStartShown = {},
            onErrorShown = {}, onStarFailureShown = {}, nowMillis = java.time.Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
        )
    }

    private val octocat = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))

    @Composable
    private fun SettingsPreview(settings: UserSettings = UserSettings(), section: SettingsSection? = null) {
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
            section = section,
        )
    }

    @Test
    fun discussion_light() = snapshot("discussion_light", darkTheme = false, awaitText = "Use the cursor", awaitGoneTag = MARKDOWN_PENDING_TAG) {
        val repo = RepoId("paperclipai", "paperclip")
        val summary = fr.arthurbrugiere.forgeline.core.testing.discussionSummary(412, "How do I page through runs?", category = "Q&A", comments = 2, isAnswered = true, upvotes = 3)
        fr.arthurbrugiere.forgeline.discussion.DiscussionScreen(
            state = fr.arthurbrugiere.forgeline.discussion.DiscussionUiState(
                repo, 412, summary,
                fr.arthurbrugiere.forgeline.core.model.Discussion(
                    summary, "The list stops at **30 runs** and I can't find how to get the rest.",
                    listOf(
                        fr.arthurbrugiere.forgeline.core.testing.discussionComment(
                            "c1", "Use the cursor the list gives back as `next`.", login = "hubot", isAnswer = true, upvotes = 5,
                            replies = listOf(fr.arthurbrugiere.forgeline.core.testing.discussionComment("r1", "Thanks, that was it.", login = "alice")),
                        ),
                        fr.arthurbrugiere.forgeline.core.testing.discussionComment("c2", "Same question here.", login = "carol"),
                    ),
                ),
                isLoading = false,
            ),
            signedIn = true, onBack = {}, onRefresh = {}, onOpenRepo = {}, onOpenUser = {}, onOpenInBrowser = {}, onLinkClick = {}, onSignIn = {}, onErrorShown = {},
            nowMillis = java.time.Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
        )
    }

    @Test
    fun work_light() = snapshot("work_light", darkTheme = false, awaitText = "Assigned to you") {
        val tools = RepoId("octo", "tools")
        val notes = RepoId("alice", "notes", ForgeInstance.Codeberg)
        fun found(repo: RepoId, number: Int, title: String, pull: Boolean = true) =
            fr.arthurbrugiere.forgeline.core.model.IssueSearchResult(repo, fr.arthurbrugiere.forgeline.core.testing.issueSummary(number, title, isPullRequest = pull))
        fr.arthurbrugiere.forgeline.work.WorkScreen(
            state = fr.arthurbrugiere.forgeline.work.WorkUiState(
                fr.arthurbrugiere.forgeline.core.data.work.Work(
                    mapOf(
                        fr.arthurbrugiere.forgeline.core.model.WorkKind.REVIEW_REQUESTED to listOf(found(tools, 88, "Retry uploads on slow links")),
                        fr.arthurbrugiere.forgeline.core.model.WorkKind.OWN_PULL_REQUESTS to listOf(found(notes, 12, "Add a dark theme"), found(tools, 91, "Bump the SDK")),
                        fr.arthurbrugiere.forgeline.core.model.WorkKind.ASSIGNED to listOf(found(notes, 9, "Crash on start", pull = false)),
                    ),
                    forges = listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg),
                ),
                isLoading = false,
            ),
            onBack = {}, onRefresh = {}, onOpenIssue = {}, onErrorShown = {},
            nowMillis = java.time.Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
        )
    }
}
