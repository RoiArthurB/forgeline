package fr.arthurbrugiere.forgeline.discussion

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Discussion
import fr.arthurbrugiere.forgeline.core.model.DiscussionReactions
import fr.arthurbrugiere.forgeline.core.model.DiscussionUpvotes
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.discussionComment
import fr.arthurbrugiere.forgeline.core.testing.discussionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscussionViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repo = RepoId("octo", "repo")
    private val repos = FakeRepoRepository()
    private val read = Discussion(discussionSummary(7, "How do I page?", comments = 1), "I can't find it.", listOf(discussionComment("c1", "Use the cursor.")))

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun a_discussion_is_read_when_its_page_opens() = test {
        repos.discussions[7] = ForgeResult.Success(read)

        val viewModel = DiscussionViewModel(repo, 7, repos)
        assertThat(viewModel.state.value.isLoading).isTrue()
        advanceUntilIdle()

        assertThat(viewModel.state.value.discussion).isEqualTo(read)
        assertThat(viewModel.state.value.summary).isEqualTo(read.summary)
        assertThat(viewModel.state.value.isLoading).isFalse()
        assertThat(viewModel.state.value.webUrl).isEqualTo("https://github.com/octo/repo/discussions/7")
        assertThat(repos.calls).containsExactly("discussion:octo/repo#7")
    }

    @Test
    fun what_its_list_said_of_it_heads_the_page_while_the_rest_loads() = test {
        repos.seenDiscussions[7] = discussionSummary(7, "How do I page?")
        repos.discussions[7] = ForgeResult.Success(read)
        repos.gate = CompletableDeferred()

        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()

        assertThat(viewModel.state.value.summary?.title).isEqualTo("How do I page?")
        assertThat(viewModel.state.value.discussion).isNull()
        repos.gate!!.complete(Unit)
        advanceUntilIdle()
        // What was read replaces what the list said: one more comment came since.
        assertThat(viewModel.state.value.summary?.comments).isEqualTo(1)
    }

    @Test
    fun a_discussion_that_can_t_be_read_says_why_and_can_be_asked_for_again() = test {
        repos.discussions[7] = ForgeResult.Failure(ForgeError.Network)
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.discussion).isNull()

        repos.discussions[7] = ForgeResult.Success(read)
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.discussion).isEqualTo(read)
    }

    @Test
    fun a_refresh_that_fails_leaves_what_was_read_and_is_said_once() = test {
        repos.discussions[7] = ForgeResult.Success(read)
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()
        repos.discussions[7] = ForgeResult.Failure(ForgeError.Network)

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.discussion).isEqualTo(read)
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        viewModel.errorShown()
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun a_comment_goes_to_the_discussion_and_it_is_read_again() = test {
        val commentable = read.copy(id = "D_1")
        repos.discussions[7] = ForgeResult.Success(commentable)
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()
        assertThat(viewModel.state.value.canComment).isTrue()

        viewModel.send("  Found it.  ")
        assertThat(viewModel.state.value.sending).isEqualTo(DiscussionViewModel.COMMENT)
        advanceUntilIdle()

        assertThat(repos.calls).containsExactly("discussion:octo/repo#7", "discussionComment:octo/repo#7:Found it.", "discussion:octo/repo#7").inOrder()
        assertThat(viewModel.state.value.sending).isNull()
        assertThat(viewModel.state.value.sent?.first).isEqualTo(DiscussionViewModel.COMMENT)
    }

    @Test
    fun a_reply_goes_under_its_comment_and_an_answer_is_picked() = test {
        repos.discussions[7] = ForgeResult.Success(read.copy(id = "D_1"))
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()

        viewModel.send("Did it help?", replyTo = read.comments[0])
        advanceUntilIdle()
        assertThat(viewModel.state.value.sent?.first).isEqualTo("c1")
        viewModel.setAnswer(read.comments[0], answer = true)
        advanceUntilIdle()

        assertThat(repos.calls).containsAtLeast("discussionComment:octo/repo#7 under c1:Did it help?", "discussionAnswer:octo/repo:c1:true").inOrder()
        assertThat(viewModel.state.value.sent?.first).isEqualTo("answer:c1")
    }

    @Test
    fun a_comment_refused_says_why_and_nothing_is_read_again() = test {
        repos.discussions[7] = ForgeResult.Success(read.copy(id = "D_1"))
        repos.discussionWriteFailure = ForgeError.Http(403, "Discussion is locked")
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()

        viewModel.send("Hello")
        advanceUntilIdle()

        assertThat(viewModel.state.value.sendError).isEqualTo(ForgeError.Http(403, "Discussion is locked"))
        assertThat(viewModel.state.value.sent).isNull()
        assertThat(repos.calls.count { it.startsWith("discussion:") }).isEqualTo(1)
        viewModel.sendErrorShown()
        assertThat(viewModel.state.value.sendError).isNull()
    }

    @Test
    fun one_write_at_a_time_and_none_on_a_locked_discussion() = test {
        repos.discussions[7] = ForgeResult.Success(read.copy(id = "D_1"))
        repos.gate = CompletableDeferred()
        val viewModel = DiscussionViewModel(repo, 7, repos)
        repos.gate!!.complete(Unit)
        advanceUntilIdle()
        repos.gate = CompletableDeferred()

        viewModel.send("One")
        viewModel.send("Two")
        repos.gate!!.complete(Unit)
        advanceUntilIdle()
        assertThat(repos.calls.filter { it.startsWith("discussionComment") }).containsExactly("discussionComment:octo/repo#7:One")

        assertThat(viewModel.state.value.copy(discussion = read.copy(id = "D_1", locked = true)).canComment).isFalse()
        assertThat(viewModel.state.value.copy(discussion = read).canComment).isFalse()
    }

    @Test
    fun a_reaction_is_given_to_the_text_a_comment_or_a_reply_and_the_forge_s_counts_replace_the_old() = test {
        val reply = discussionComment("r1", "Thanks!")
        val reactable = read.copy(
            id = "D1",
            reactions = DiscussionReactions(canReact = true),
            comments = listOf(discussionComment("c1", "Use the cursor.", replies = listOf(reply.copy(reactions = DiscussionReactions(canReact = true))))),
        )
        repos.discussions[7] = ForgeResult.Success(reactable)
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()
        repos.discussionReactions["c1"] = DiscussionReactions(mapOf(Reaction.HEART to 5), setOf(Reaction.HEART), canReact = true)

        viewModel.react("D1", Reaction.ROCKET)
        viewModel.react("c1", Reaction.HEART)
        viewModel.react("r1", Reaction.EYES)
        advanceUntilIdle()

        val discussion = viewModel.state.value.discussion!!
        assertThat(discussion.reactions.counts).containsExactly(Reaction.ROCKET, 1)
        assertThat(discussion.comments[0].reactions.counts).containsExactly(Reaction.HEART, 5)
        assertThat(discussion.comments[0].replies[0].reactions.given).containsExactly(Reaction.EYES)
        // Nothing else is read again: the forge answered what each now has.
        assertThat(repos.calls).containsExactly(
            "discussion:octo/repo#7",
            "discussionReaction:octo/repo:D1:ROCKET:true",
            "discussionReaction:octo/repo:c1:HEART:true",
            "discussionReaction:octo/repo:r1:EYES:true",
        ).inOrder()
    }

    @Test
    fun a_reaction_the_reader_gave_is_taken_back() = test {
        val given = read.copy(id = "D1", reactions = DiscussionReactions(mapOf(Reaction.HEART to 2), setOf(Reaction.HEART), canReact = true))
        repos.discussions[7] = ForgeResult.Success(given)
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()

        viewModel.react("D1", Reaction.HEART)
        advanceUntilIdle()

        assertThat(repos.calls.last()).isEqualTo("discussionReaction:octo/repo:D1:HEART:false")
    }

    @Test
    fun a_second_tap_while_the_first_is_on_its_way_is_not_sent_and_a_refused_one_is_said() = test {
        repos.discussions[7] = ForgeResult.Success(read.copy(id = "D1"))
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()
        repos.gate = CompletableDeferred()
        repos.discussionWriteFailure = ForgeError.Network

        viewModel.react("D1", Reaction.HEART)
        viewModel.react("D1", Reaction.HEART)
        repos.gate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(repos.calls.count { it.startsWith("discussionReaction") }).isEqualTo(1)
        assertThat(viewModel.state.value.reactionError).isEqualTo(ForgeError.Network)
        // What was shown stays as it was.
        assertThat(viewModel.state.value.discussion?.reactions).isEqualTo(read.reactions)
        viewModel.reactionErrorShown()
        assertThat(viewModel.state.value.reactionError).isNull()
    }

    @Test
    fun an_upvote_is_given_to_the_discussion_or_a_comment_and_taken_back() = test {
        val votable = read.copy(id = "D1", canUpvote = true, comments = listOf(discussionComment("c1", "Use the cursor.", upvotes = 4).copy(upvoted = true, canUpvote = true)))
        repos.discussions[7] = ForgeResult.Success(votable)
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()

        viewModel.upvote("D1")
        viewModel.upvote("c1")
        advanceUntilIdle()

        val discussion = viewModel.state.value.discussion!!
        assertThat(discussion.upvoteTally).isEqualTo(DiscussionUpvotes(1, given = true, canGive = true))
        // The header reads the count from what was read.
        assertThat(viewModel.state.value.summary?.upvotes).isEqualTo(1)
        assertThat(discussion.comments[0].upvoteTally).isEqualTo(DiscussionUpvotes(3, given = false, canGive = true))
        assertThat(repos.calls.drop(1)).containsExactly("discussionUpvote:octo/repo:D1:true", "discussionUpvote:octo/repo:c1:false").inOrder()
    }

    @Test
    fun an_upvote_that_didn_t_go_through_is_said_and_leaves_the_count() = test {
        repos.discussions[7] = ForgeResult.Success(read.copy(id = "D1", canUpvote = true))
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()
        repos.discussionWriteFailure = ForgeError.Network

        viewModel.upvote("D1")
        advanceUntilIdle()

        assertThat(viewModel.state.value.reactionError).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.discussion?.upvoted).isFalse()
    }
}
