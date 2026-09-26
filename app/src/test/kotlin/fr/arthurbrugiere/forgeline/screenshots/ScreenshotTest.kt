package fr.arthurbrugiere.forgeline.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
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
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            // Ember palette keeps screenshots independent of the emulated wallpaper.
            ForgelineTheme(darkTheme = darkTheme, dynamicColor = false, amoledBlack = amoledBlack, content = content)
        }
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

    private val trendingState = TrendingUiState(
        items = listOf(
            TrendingItem(
                trendingRepo("paperclipai/paperclip", stars = 85_955, periodStars = 2_109)
                    .copy(builtBy = listOf("cryppadotta", "devinfoley", "nickyleach").map { ForgeUser(it, null, null) }),
                starred = true,
            ),
            TrendingItem(trendingRepo("vectorize-io/hindsight", stars = 30_641, periodStars = 1_653), starred = false),
            TrendingItem(trendingRepo("anthropics/claude-code-action", stars = 8_970, periodStars = 15, description = null), starred = false),
        ),
        updatedAtMillis = 0L,
    )

    @Test
    fun trending_light() = snapshot("trending_light", darkTheme = false) { TrendingPreview() }

    @Test
    fun trending_dark() = snapshot("trending_dark", darkTheme = true) { TrendingPreview() }

    @Composable
    private fun TrendingPreview() {
        TrendingScreen(
            state = trendingState, onPeriodChange = {}, onRefresh = {}, onToggleStar = {}, onOpenRepo = {},
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
    fun repo_readme_light() = snapshot("repo_readme_light", darkTheme = false) {
        RepoPreview(repoState)
        composeRule.waitForIdle()
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

    @Composable
    private fun RepoPreview(state: RepoUiState) {
        RepoScreen(
            state = state, signedIn = true, onBack = {}, onRefresh = {}, onSelectTab = {}, onRetryTab = {}, onToggleStar = {},
            onOpenDirectory = {}, onOpenParentDirectory = {}, onOpenFile = {}, onLinkClick = {}, onOpenInBrowser = {},
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
            onDynamicColorChange = {},
            onAmoledBlackChange = {},
            onOpenCredits = {},
            onOpenSourceCode = {},
            onBack = {},
        )
    }
}
