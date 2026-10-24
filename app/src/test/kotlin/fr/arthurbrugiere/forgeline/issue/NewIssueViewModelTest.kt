package fr.arthurbrugiere.forgeline.issue

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.InMemoryConversationDao
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class NewIssueViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeIssueApi()
    private val accounts = FakeAccountRepository()
    private val repository = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, InMemoryConversationDao(), Clock.systemUTC())
    private val drafts = IssueDrafts()
    private val repo = RepoId("octo", "repo")

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        block()
    }

    private fun viewModel() = NewIssueViewModel(repo, repository, drafts)

    @Test
    fun an_issue_needs_a_title_but_not_a_description() = test {
        val viewModel = viewModel()
        assertThat(viewModel.state.value.canSend).isFalse()

        viewModel.bodyChanged("Steps")
        assertThat(viewModel.state.value.canSend).isFalse()
        viewModel.titleChanged("   ")
        assertThat(viewModel.state.value.canSend).isFalse()

        viewModel.titleChanged("Crash")
        viewModel.bodyChanged("")
        assertThat(viewModel.state.value.canSend).isTrue()
    }

    @Test
    fun sending_without_a_title_asks_the_forge_nothing() = test {
        val viewModel = viewModel()
        viewModel.bodyChanged("Steps")

        viewModel.send()
        advanceUntilIdle()

        // Only what the form opened with: the repository's templates.
        assertThat(api.calls).containsExactly("templates:octo/repo")
    }

    @Test
    fun sending_opens_the_issue_trimmed_and_hands_over_its_conversation() = test {
        val viewModel = viewModel()
        viewModel.titleChanged("  Crash on start ")
        viewModel.bodyChanged("Steps:\n1. Open it\n")

        viewModel.send()
        advanceUntilIdle()

        assertThat(api.opened).containsExactly("octo/repo: Crash on start / Steps:\n1. Open it")
        assertThat(viewModel.state.value.created).isEqualTo(IssueRef(repo, 100))
        assertThat(viewModel.state.value.isSending).isFalse()
        // The conversation it becomes is already kept: it opens without waiting for the forge.
        assertThat(repository.cached(IssueRef(repo, 100))?.issue?.title).isEqualTo("Crash on start")
    }

    @Test
    fun while_it_is_on_its_way_it_can_t_be_sent_twice() = test {
        api.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.titleChanged("Crash")

        viewModel.send()
        advanceUntilIdle()
        assertThat(viewModel.state.value.isSending).isTrue()
        assertThat(viewModel.state.value.canSend).isFalse()
        viewModel.send()
        api.gate?.complete(Unit)
        advanceUntilIdle()

        assertThat(api.opened).hasSize(1)
    }

    @Test
    fun a_failure_keeps_what_was_written_and_says_why() = test {
        api.createFailure = ForgeError.Network
        val viewModel = viewModel()
        viewModel.titleChanged("Crash")
        viewModel.bodyChanged("Steps")

        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.error).isEqualTo(ForgeError.Network)
        assertThat(state.title).isEqualTo("Crash")
        assertThat(state.body).isEqualTo("Steps")
        assertThat(state.created).isNull()
        assertThat(state.canSend).isTrue()

        // Writing again takes the error away.
        viewModel.bodyChanged("Steps, again")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun without_an_account_on_the_forge_nothing_is_sent() = test {
        val codeberg = RepoId("octo", "repo", ForgeInstance.Codeberg)
        val viewModel = NewIssueViewModel(codeberg, repository, drafts)
        viewModel.titleChanged("Crash")

        viewModel.send()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Unauthorized)
        assertThat(api.opened).isEmpty()
    }

    @Test
    fun an_issue_left_half_written_is_there_when_the_form_reopens() = test {
        viewModel().run {
            titleChanged("Crash")
            bodyChanged("Steps")
        }

        // The form was left (Back by mistake, or to look something up) and opened again.
        val reopened = viewModel()

        assertThat(reopened.state.value.title).isEqualTo("Crash")
        assertThat(reopened.state.value.body).isEqualTo("Steps")
        // Each repository has its own.
        assertThat(NewIssueViewModel(RepoId("octo", "other"), repository, drafts).state.value.title).isEmpty()
    }

    @Test
    fun an_issue_that_was_opened_is_not_offered_again() = test {
        val viewModel = viewModel()
        viewModel.titleChanged("Crash")
        viewModel.send()
        advanceUntilIdle()

        assertThat(viewModel().state.value.title).isEmpty()
    }

    private val bug = IssueTemplate("bug.md", "Bug report", "Something broke", title = "Bug: ", body = "## Steps\n")
    private val listedOnly = IssueTemplate("Feature", "Feature")

    @Test
    fun a_new_issue_starts_from_a_template_the_repository_offers() = test {
        api.templates = listOf(bug)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.offersTemplates).isTrue()

        viewModel.useTemplate(bug)

        assertThat(viewModel.state.value.title).isEqualTo("Bug: ")
        assertThat(viewModel.state.value.body).isEqualTo("## Steps\n")
        // Written on from there: no longer offered, and kept as the draft.
        assertThat(viewModel.state.value.offersTemplates).isFalse()
        assertThat(drafts[repo]).isEqualTo(IssueDraft("Bug: ", "## Steps\n"))
    }

    @Test
    fun a_template_listed_without_its_text_is_asked_for_it_when_picked() = test {
        api.templates = listOf(listedOnly)
        api.templateTexts = mapOf("Feature" to "## Proposal\n")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.useTemplate(listedOnly)
        assertThat(viewModel.state.value.pickingTemplate).isEqualTo("Feature")
        advanceUntilIdle()

        assertThat(api.calls).contains("template:Feature")
        assertThat(viewModel.state.value.pickingTemplate).isNull()
        assertThat(viewModel.state.value.body).isEqualTo("## Proposal\n")
    }

    @Test
    fun a_template_that_can_t_be_had_leaves_the_form_empty_and_says_so() = test {
        api.templates = listOf(listedOnly)
        val viewModel = viewModel()
        advanceUntilIdle()
        api.manageFailure = ForgeError.Network

        viewModel.useTemplate(listedOnly)
        advanceUntilIdle()

        assertThat(viewModel.state.value.templateError).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.body).isEmpty()
        assertThat(viewModel.state.value.offersTemplates).isTrue()
    }

    @Test
    fun editing_an_issue_offers_no_template() = test {
        api.templates = listOf(bug)
        val viewModel = editing()
        advanceUntilIdle()

        assertThat(viewModel.state.value.offersTemplates).isFalse()
        assertThat(api.calls).doesNotContain("templates:octo/repo")
    }

    private val edited = fr.arthurbrugiere.forgeline.core.model.IssueRef(repo, 7)

    /** The form opened on conversation #7, which was just read: "An issue" / "Body of #7". */
    private suspend fun editing(): NewIssueViewModel {
        api.issues[edited] = fr.arthurbrugiere.forgeline.core.testing.issueDetails(edited)
        repository.issue(edited)
        return NewIssueViewModel(repo, repository, drafts, edited)
    }

    @Test
    fun editing_starts_from_the_title_and_text_as_last_read() = test {
        val state = editing().state.value

        assertThat(state.editing).isEqualTo(edited)
        assertThat(state.title).isEqualTo("An issue")
        assertThat(state.body).isEqualTo("Body of #7")
        assertThat(state.isPullRequest).isFalse()
        assertThat(state.canSend).isTrue()
    }

    @Test
    fun editing_ignores_the_new_issue_being_written_and_leaves_it_alone() = test {
        drafts.keep(repo, IssueDraft("Not opened yet", "Still thinking"))
        val viewModel = editing()

        viewModel.titleChanged("A better title")

        assertThat(viewModel.state.value.title).isEqualTo("A better title")
        assertThat(drafts[repo]).isEqualTo(IssueDraft("Not opened yet", "Still thinking"))
    }

    @Test
    fun saving_changes_the_conversation_and_opens_no_issue() = test {
        val viewModel = editing()
        viewModel.titleChanged("  A better title ")
        viewModel.bodyChanged("")

        viewModel.send()
        assertThat(viewModel.state.value.isSending).isTrue()
        advanceUntilIdle()

        assertThat(api.edited).containsExactly("octo/repo#7: A better title / ")
        assertThat(api.opened).isEmpty()
        // The form gives way to the conversation it changed.
        assertThat(viewModel.state.value.created).isEqualTo(edited)
        assertThat(repository.cached(edited)?.issue?.title).isEqualTo("A better title")
    }

    @Test
    fun changes_the_forge_refuses_stay_written_and_say_why() = test {
        val viewModel = editing()
        viewModel.titleChanged("A better title")
        api.editFailure = ForgeError.Http(403, "no")

        viewModel.send()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Http(403, "no"))
        assertThat(viewModel.state.value.title).isEqualTo("A better title")
        assertThat(viewModel.state.value.created).isNull()
    }

    @Test
    fun an_edited_conversation_still_needs_a_title() = test {
        val viewModel = editing()

        viewModel.titleChanged("  ")

        assertThat(viewModel.state.value.canSend).isFalse()
    }

    @Test
    fun a_pull_request_being_edited_is_said_to_be_one() = test {
        api.issues[edited] = fr.arthurbrugiere.forgeline.core.testing.issueDetails(edited)
            .copy(pullRequest = fr.arthurbrugiere.forgeline.core.model.PullRequestInfo(false, false, "main", "fix", 1, 1, 1, 1))
        repository.issue(edited)

        assertThat(NewIssueViewModel(repo, repository, drafts, edited).state.value.isPullRequest).isTrue()
    }
}
