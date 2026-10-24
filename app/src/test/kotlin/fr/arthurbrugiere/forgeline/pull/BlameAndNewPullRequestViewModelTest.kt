package fr.arthurbrugiere.forgeline.pull

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.pull.DefaultPullRequestRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoSnapshot
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakePullRequestApi
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.commit
import fr.arthurbrugiere.forgeline.core.testing.repoDetails
import fr.arthurbrugiere.forgeline.file.FileTarget
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlameAndNewPullRequestViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakePullRequestApi()
    private val accounts = FakeAccountRepository()
    private val pulls = DefaultPullRequestRepository(FakeForgeClients(pulls = api), accounts)
    private val repos = FakeRepoRepository()
    private val tools = RepoId("octo", "repo")
    private val file = FileTarget(tools, "src/Main.kt", "main")

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun a_file_is_read_under_the_commits_that_last_left_its_lines() = test {
        repos.files["src/Main.kt"] = ForgeResult.Success("package x\n\nfun main() {}\n// end\n")
        api.blame = listOf(BlameRange(1, 2, commit("a1", "Start")), BlameRange(3, 3, commit("b2", "Add main")), BlameRange(4, 4, commit("a1", "Start")))

        val viewModel = BlameViewModel(file, repos, pulls)
        assertThat(viewModel.state.value.blocks).isEqualTo(Loadable.Loading)
        advanceUntilIdle()

        val blocks = (viewModel.state.value.blocks as Loadable.Loaded).value
        assertThat(blocks.map { Triple(it.commit.sha, it.startLine, it.lines) }).containsExactly(
            Triple("a1", 1, listOf("package x", "")), Triple("b2", 3, listOf("fun main() {}")), Triple("a1", 4, listOf("// end")),
        ).inOrder()
        assertThat(api.calls).containsExactly("blame:octo/repo:main:src/Main.kt")
    }

    @Test
    fun a_range_past_the_end_of_the_file_holds_what_there_is() {
        // The file moved on between the two answers: nothing is invented, nothing crashes.
        val blocks = BlameViewModel.blocks("a\nb\n", listOf(BlameRange(1, 1, commit("a1")), BlameRange(2, 5, commit("b2")), BlameRange(6, 9, commit("c3"))))

        assertThat(blocks.map { it.commit.sha to it.lines }).containsExactly("a1" to listOf("a"), "b2" to listOf("b")).inOrder()
    }

    @Test
    fun blame_the_forge_keeps_for_signed_in_people_says_so_and_can_be_asked_again() = test {
        repos.files["src/Main.kt"] = ForgeResult.Success("a\n")
        api.failure = ForgeError.Unauthorized
        val viewModel = BlameViewModel(file, repos, pulls)
        advanceUntilIdle()
        assertThat(viewModel.state.value.blocks).isEqualTo(Loadable.Failed(ForgeError.Unauthorized))

        api.failure = null
        api.blame = listOf(BlameRange(1, 1, commit("a1")))
        viewModel.retry()
        advanceUntilIdle()

        assertThat((viewModel.state.value.blocks as Loadable.Loaded).value).hasSize(1)
    }

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")

    private fun form(): NewPullRequestViewModel {
        repos.refs = ForgeResult.Success(GitRefs(listOf("fix/slow-uploads", "main", "release"), listOf("v1")))
        repos.snapshot.value = RepoSnapshot(repoDetails("octo/repo", defaultBranch = "main"), null, null)
        return NewPullRequestViewModel(tools, repos, pulls)
    }

    @Test
    fun a_pull_request_goes_into_the_default_branch_unless_another_is_picked() = test {
        val viewModel = form()
        advanceUntilIdle()

        val state = viewModel.state.value
        // The default branch heads the list and is where the change goes; where it comes from is the reader's to say.
        assertThat((state.branches as Loadable.Loaded).value).containsExactly("main", "fix/slow-uploads", "release").inOrder()
        assertThat(state.base).isEqualTo("main")
        assertThat(state.head).isNull()
        assertThat(state.canSend).isFalse()
    }

    @Test
    fun the_branch_picked_names_a_title_still_empty_and_leaves_one_already_written() = test {
        val viewModel = form()
        advanceUntilIdle()

        viewModel.headChanged("fix/slow-uploads")
        assertThat(viewModel.state.value.title).isEqualTo("Slow uploads")
        viewModel.titleChanged("Retry uploads")
        viewModel.headChanged("release")

        assertThat(viewModel.state.value.title).isEqualTo("Retry uploads")
        assertThat(viewModel.state.value.canSend).isTrue()
    }

    @Test
    fun a_branch_into_itself_or_without_a_title_is_not_sent() = test {
        val viewModel = form()
        advanceUntilIdle()

        viewModel.headChanged("main")
        assertThat(viewModel.state.value.canSend).isFalse()
        viewModel.headChanged("release")
        viewModel.titleChanged("  ")
        assertThat(viewModel.state.value.canSend).isFalse()
        viewModel.send()
        advanceUntilIdle()

        assertThat(api.opened).isEmpty()
    }

    @Test
    fun the_pull_request_opened_takes_the_form_s_place() = test {
        signIn()
        val viewModel = form()
        advanceUntilIdle()
        viewModel.headChanged("fix/slow-uploads")
        viewModel.titleChanged(" Retry uploads ")
        viewModel.bodyChanged("Three times.")
        viewModel.baseChanged("release")
        viewModel.draftChanged(true)

        viewModel.send()
        assertThat(viewModel.state.value.isSending).isTrue()
        advanceUntilIdle()

        assertThat(api.opened).containsExactly(NewPullRequest("Retry uploads", "Three times.", head = "fix/slow-uploads", base = "release", draft = true))
        assertThat(viewModel.state.value.created).isEqualTo(IssueRef(tools, 100, isPullRequest = true))
    }

    @Test
    fun a_pull_request_the_forge_refuses_keeps_what_was_written() = test {
        signIn()
        val viewModel = form()
        advanceUntilIdle()
        viewModel.headChanged("release")
        api.writeFailure = ForgeError.Http(422, "No commits between main and release")

        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.error).isEqualTo(ForgeError.Http(422, "No commits between main and release"))
        assertThat(state.created).isNull()
        assertThat(state.title).isEqualTo("Release")
        assertThat(state.canSend).isTrue()
    }

    @Test
    fun branches_that_cannot_be_listed_say_so_and_can_be_asked_again() = test {
        repos.refs = ForgeResult.Failure(ForgeError.Network)
        val viewModel = NewPullRequestViewModel(tools, repos, pulls)
        advanceUntilIdle()
        assertThat(viewModel.state.value.branches).isEqualTo(Loadable.Failed(ForgeError.Network))

        repos.refs = ForgeResult.Success(GitRefs(listOf("dev", "trunk"), emptyList()))
        viewModel.loadBranches()
        advanceUntilIdle()

        // No default branch known for this repository: the first one listed stands in.
        assertThat(viewModel.state.value.base).isEqualTo("dev")
    }

    private val gitlabDefault = IssueTemplate("Default", "Default")
    private val docs = IssueTemplate("Documentation", "Documentation")

    @Test
    fun a_repository_s_only_template_fills_the_description_from_its_default_branch() = test {
        signIn()
        api.templates = listOf(IssueTemplate("PULL_REQUEST_TEMPLATE.md", "PULL_REQUEST_TEMPLATE.md", body = "## Summary\n"))
        val viewModel = form()
        advanceUntilIdle()

        assertThat(viewModel.state.value.body).isEqualTo("## Summary\n")
        assertThat(api.calls).contains("templates:octo/repo@main")
        // One template is no choice: none is offered.
        assertThat(viewModel.state.value.offersTemplates).isFalse()
    }

    @Test
    fun among_several_the_default_one_is_filled_in_and_the_others_offered_until_the_reader_writes() = test {
        signIn()
        api.templates = listOf(docs, gitlabDefault)
        api.templateTexts = mapOf("Default" to "## What does this MR do?\n", "Documentation" to "## Docs\n")
        val viewModel = form()
        advanceUntilIdle()
        assertThat(viewModel.state.value.body).isEqualTo("## What does this MR do?\n")
        assertThat(viewModel.state.value.template).isEqualTo("Default")
        assertThat(viewModel.state.value.offersTemplates).isTrue()

        viewModel.useTemplate(docs)
        assertThat(viewModel.state.value.pickingTemplate).isEqualTo("Documentation")
        advanceUntilIdle()
        assertThat(viewModel.state.value.body).isEqualTo("## Docs\n")
        assertThat(viewModel.state.value.template).isEqualTo("Documentation")

        viewModel.bodyChanged("## Docs\nThe retry page.")
        assertThat(viewModel.state.value.offersTemplates).isFalse()
    }

    @Test
    fun a_template_arriving_after_the_reader_started_writing_leaves_their_text() = test {
        signIn()
        api.templates = listOf(IssueTemplate("pull_request_template.md", "pull_request_template.md", body = "## Summary\n"))
        val viewModel = form()
        // Written before the branches, and so the templates, are in.
        viewModel.bodyChanged("Three times.")
        advanceUntilIdle()

        assertThat(viewModel.state.value.body).isEqualTo("Three times.")
    }

    @Test
    fun several_templates_without_a_default_fill_nothing() = test {
        signIn()
        api.templates = listOf(docs, IssueTemplate("Security Release", "Security Release"))
        val viewModel = form()
        advanceUntilIdle()

        assertThat(viewModel.state.value.body).isEmpty()
        assertThat(viewModel.state.value.offersTemplates).isTrue()
        assertThat(api.calls.filter { it.startsWith("template:") }).isEmpty()
    }

    @Test
    fun a_template_that_names_the_branches_follows_them_until_it_is_written_on() = test {
        signIn()
        api.templates = listOf(IssueTemplate("Default", "Default", body = "From %{source_branch} into %{target_branch}.\n\n%{first_multiline_commit}"))
        val viewModel = form()
        advanceUntilIdle()
        assertThat(viewModel.state.value.body).isEqualTo("From  into main.\n\n")

        viewModel.headChanged("fix/slow-uploads")
        assertThat(viewModel.state.value.body).isEqualTo("From fix/slow-uploads into main.\n\n")
        viewModel.bodyChanged("From fix/slow-uploads into main.\n\nThree retries.")
        viewModel.baseChanged("release")

        assertThat(viewModel.state.value.body).isEqualTo("From fix/slow-uploads into main.\n\nThree retries.")
    }

    @Test
    fun a_template_that_can_t_be_had_leaves_the_description_empty_and_says_so() = test {
        signIn()
        api.templates = listOf(docs, gitlabDefault)
        val viewModel = form()
        advanceUntilIdle()
        api.failure = ForgeError.Network

        viewModel.useTemplate(docs)
        advanceUntilIdle()

        assertThat(viewModel.state.value.templateError).isEqualTo(ForgeError.Network)
    }
}
