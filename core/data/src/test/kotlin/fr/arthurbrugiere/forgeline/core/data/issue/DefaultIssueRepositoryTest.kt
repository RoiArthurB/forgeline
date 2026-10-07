package fr.arthurbrugiere.forgeline.core.data.issue

import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.testing.Rendezvous
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class DefaultIssueRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val api = FakeIssueApi()
    private val accounts = FakeAccountRepository()
    private var now = 1_000L
    private val clock = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val repository = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)

    @After
    fun closeDatabase() = database.close()

    @Test
    fun a_conversation_is_kept_on_disk_for_the_next_launch() = runTest {
        val review = TimelineItem.Review(2, ForgeUser("rev", null, null), ReviewState.APPROVED, null, Instant.parse("2026-09-26T09:00:00Z"))
        val labeled = TimelineItem.Labeled(true, Label("bug", "d73a4a"), ForgeUser("maint", null, null), Instant.parse("2026-09-26T09:30:00Z"))
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi"), review, labeled), nextPage = 2, lastPage = 6)
        repository.issue(ref)
        repository.timeline(ref, 1)

        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)

        assertThat(relaunched.cached(ref)).isNull()
        val stored = relaunched.stored(ref)!!
        assertThat(stored.issue).isEqualTo(issueDetails(ref))
        // The last page too: the rest of a long conversation is asked all at once from the copy kept.
        assertThat(stored.firstPage).isEqualTo(TimelinePage(listOf(comment(1, "Hi"), review, labeled), nextPage = 2, lastPage = 6))
        assertThat(relaunched.cached(ref)).isEqualTo(stored)
    }

    @Test
    fun only_the_most_recently_viewed_conversations_stay_on_disk() = runTest {
        repeat(DefaultIssueRepository.STORED_CONVERSATIONS + 1) { number ->
            val other = IssueRef(RepoId("octo", "repo"), number + 100)
            api.issues[other] = issueDetails(other)
            now += 1
            repository.issue(other)
        }

        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)

        assertThat(relaunched.stored(IssueRef(RepoId("octo", "repo"), 100))).isNull()
        assertThat(relaunched.stored(IssueRef(RepoId("octo", "repo"), 101))).isNotNull()
    }
    private val ref = IssueRef(RepoId("octo", "repo"), 7)

    @Test
    fun a_viewed_conversation_is_remembered_for_an_instant_reopen() = runTest {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi")), null)

        assertThat(repository.cached(ref)).isNull()
        repository.issue(ref)
        repository.timeline(ref, 1)

        val cached = repository.cached(ref)!!
        assertThat(cached.issue).isEqualTo(issueDetails(ref))
        assertThat(cached.firstPage?.items).containsExactly(comment(1, "Hi"))
    }

    @Test
    fun failures_are_returned_and_do_not_erase_the_cache() = runTest {
        api.issues[ref] = issueDetails(ref)
        repository.issue(ref)
        api.failure = ForgeError.Network

        assertThat(repository.issue(ref)).isEqualTo(ForgeResult.Failure(ForgeError.Network))
        assertThat(repository.cached(ref)?.issue).isEqualTo(issueDetails(ref))
    }

    @Test
    fun calls_are_anonymous_signed_out_and_authenticated_signed_in() = runTest {
        api.issues[ref] = issueDetails(ref)
        repository.issue(ref)
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "ghp_token")
        repository.issue(ref)

        assertThat(api.tokens).containsExactly(null, "ghp_token").inOrder()
    }

    @Test
    fun the_cache_is_bounded() = runTest {
        repeat(DefaultIssueRepository.CACHE_SIZE + 5) { number ->
            val each = IssueRef(ref.repo, number)
            api.issues[each] = issueDetails(each)
            repository.issue(each)
        }

        assertThat(repository.cached(IssueRef(ref.repo, 0))).isNull()
        assertThat(repository.cached(IssueRef(ref.repo, DefaultIssueRepository.CACHE_SIZE + 4))).isNotNull()
    }

    @Test
    fun a_conversation_loaded_ahead_is_kept_until_it_has_newer_activity() = runTest {
        api.issues[ref] = issueDetails(ref)
        now = Instant.parse("2026-09-29T10:00:00Z").toEpochMilli()

        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))).isTrue()
        assertThat(DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock).stored(ref)?.issue).isEqualTo(issueDetails(ref))

        // Nothing happened since it was kept: no request.
        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T09:30:00Z"))).isFalse()
        // New activity after it was kept: loaded again.
        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T10:30:00Z"))).isTrue()
        assertThat(api.calls).containsExactly(
            "issue:octo/repo#7", "timeline:octo/repo#7@1", "issue:octo/repo#7", "timeline:octo/repo#7@1",
        ).inOrder()
    }

    @Test
    fun a_conversation_the_forge_cannot_serve_is_not_asked_again_until_it_moves() = runTest {
        // Regression: a deleted or private conversation was never kept, so every sync asked for it again.
        now = Instant.parse("2026-09-29T10:00:00Z").toEpochMilli()

        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))).isTrue()
        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))).isFalse()
        assertThat(repository.stored(ref)).isNull()
        // Asked once (its timeline alongside), then left alone.
        assertThat(api.calls.count { it == "issue:octo/repo#7" }).isEqualTo(1)
    }

    @Test
    fun a_network_failure_is_tried_again_next_time() = runTest {
        api.failure = ForgeError.Network

        repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))
        repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))

        // The issue and its timeline are asked side by side, each time.
        assertThat(api.calls.count { it == "issue:octo/repo#7" }).isEqualTo(2)
    }

    @Test
    fun a_conversation_loaded_ahead_asks_its_issue_and_timeline_together() = runTest {
        // One round trip to a far forge instead of two.
        api.issues[ref] = issueDetails(ref)
        val together = Rendezvous(2)
        val meeting = object : IssueApi by api {
            override suspend fun issue(token: String?, ref: IssueRef) = together.arrive("issue").let { api.issue(token, ref) }
            override suspend fun timeline(token: String?, ref: IssueRef, page: Int) = together.arrive("timeline").let { api.timeline(token, ref, page) }
        }
        val repository = DefaultIssueRepository(FakeForgeClients(issues = meeting), accounts, database.conversationDao(), clock)

        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))).isTrue()
        assertThat(repository.stored(ref)?.issue).isNotNull()
    }

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")

    @Test
    fun commenting_needs_an_account_on_the_conversation_s_forge() = runTest {
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "codeberg-tok")

        assertThat(repository.comment(ref, "Hello")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.posted).isEmpty()
    }

    @Test
    fun a_comment_is_posted_with_the_account_of_the_conversation_s_forge() = runTest {
        signIn()

        val result = repository.comment(ref, "Hello")

        assertThat((result as ForgeResult.Success).value.body).isEqualTo("Hello")
        assertThat(api.posted).containsExactly("${ref.repo.fullName}#${ref.number}: Hello")
        assertThat(api.tokens.last()).isEqualTo("tok")
    }

    @Test
    fun a_posted_comment_joins_the_conversation_kept_when_all_of_it_was_loaded() = runTest {
        signIn()
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi")), nextPage = null)
        repository.issue(ref)
        repository.timeline(ref, 1)

        val posted = (repository.comment(ref, "Hello") as ForgeResult.Success).value

        // Reopening shows it at once, in this session and the next.
        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"), posted).inOrder()
        assertThat(repository.cached(ref)?.issue?.comments).isEqualTo(issueDetails(ref).comments + 1)
        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)
        assertThat(relaunched.stored(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"), posted).inOrder()
    }

    @Test
    fun a_posted_comment_is_not_added_to_a_conversation_kept_only_in_part() = runTest {
        // It belongs after pages that aren't loaded: adding it to the first page would put it in the wrong place.
        signIn()
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi")), nextPage = 2)
        repository.timeline(ref, 1)

        repository.comment(ref, "Hello")

        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"))
    }

    @Test
    fun a_refused_comment_changes_nothing() = runTest {
        signIn()
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi")), nextPage = null)
        repository.timeline(ref, 1)
        api.commentFailure = ForgeError.Http(403, "locked")

        assertThat(repository.comment(ref, "Hello")).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "locked")))
        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"))
    }

    @Test
    fun opening_an_issue_needs_an_account_on_the_repository_s_forge() = runTest {
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "codeberg-tok")

        assertThat(repository.create(ref.repo, "Crash", "Steps")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.opened).isEmpty()
    }

    @Test
    fun an_issue_is_opened_with_the_account_of_the_repository_s_forge() = runTest {
        signIn()

        val created = (repository.create(ref.repo, "Crash", "Steps") as ForgeResult.Success).value

        assertThat(created.title).isEqualTo("Crash")
        assertThat(api.opened).containsExactly("${ref.repo.fullName}: Crash / Steps")
        assertThat(api.tokens.last()).isEqualTo("tok")
    }

    @Test
    fun an_opened_issue_is_kept_whole_so_it_opens_without_the_forge() = runTest {
        signIn()

        val created = (repository.create(ref.repo, "Crash", "Steps") as ForgeResult.Success).value

        assertThat(repository.cached(created.ref)).isEqualTo(CachedConversation(created, TimelinePage(emptyList(), nextPage = null)))
        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)
        assertThat(relaunched.stored(created.ref)?.issue).isEqualTo(created)
    }

    @Test
    fun an_opened_issue_is_announced_and_a_refused_one_is_not() = runTest {
        signIn()
        val announced = mutableListOf<IssueRef>()
        val listening = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { repository.changed.collect { announced += it } }

        api.createFailure = ForgeError.Http(410, "Issues are disabled for this repo")
        assertThat(repository.create(ref.repo, "Crash", "Steps")).isEqualTo(ForgeResult.Failure(ForgeError.Http(410, "Issues are disabled for this repo")))
        assertThat(announced).isEmpty()

        api.createFailure = null
        val created = (repository.create(ref.repo, "Crash", "Steps") as ForgeResult.Success).value
        assertThat(announced).containsExactly(created.ref)
        listening.cancel()
    }

    @Test
    fun nobody_signed_in_on_the_forge_can_close_a_conversation() = runTest {
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "codeberg-tok")

        assertThat(repository.canChangeState(ref, author = "me")).isFalse()
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun whoever_opened_a_conversation_can_close_it_without_asking_the_forge() = runTest {
        signIn()

        assertThat(repository.canChangeState(ref, author = "Me")).isTrue()
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun someone_else_s_conversation_can_be_closed_by_whoever_manages_the_repository() = runTest {
        signIn()
        assertThat(repository.canChangeState(ref, author = "octocat")).isFalse()

        api.access[RepoId("octo", "mine")] = RepoAccess.TRIAGE
        assertThat(repository.canChangeState(IssueRef(RepoId("octo", "mine"), 3), author = "octocat")).isTrue()
    }

    @Test
    fun the_forge_is_asked_once_per_repository_whatever_the_conversation() = runTest {
        signIn()

        repository.canChangeState(ref, author = "octocat")
        repository.canChangeState(IssueRef(ref.repo, ref.number + 1), author = null)

        assertThat(api.calls.filter { it.startsWith("access:") }).hasSize(1)
    }

    @Test
    fun a_permission_the_forge_couldn_t_tell_is_asked_again() = runTest {
        signIn()
        api.access[ref.repo] = RepoAccess.WRITE
        api.accessFailure = ForgeError.Network
        assertThat(repository.canChangeState(ref, author = "octocat")).isFalse()

        api.accessFailure = null

        assertThat(repository.canChangeState(ref, author = "octocat")).isTrue()
    }

    @Test
    fun closing_needs_an_account_on_the_conversation_s_forge() = runTest {
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "codeberg-tok")

        assertThat(repository.setOpen(ref, open = false)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.stateChanges).isEmpty()
    }

    @Test
    fun a_closed_conversation_is_kept_closed_and_reopens_open() = runTest {
        signIn()
        api.issues[ref] = issueDetails(ref)
        repository.issue(ref)
        now = 5_000L

        assertThat(repository.setOpen(ref, open = false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(api.stateChanges).containsExactly("${ref.repo.fullName}#${ref.number}: closed")
        assertThat(api.tokens.last()).isEqualTo("tok")
        assertThat(repository.cached(ref)?.issue?.state).isEqualTo(fr.arthurbrugiere.forgeline.core.model.IssueState.CLOSED)
        assertThat(repository.cached(ref)?.issue?.closedAt).isEqualTo(Instant.ofEpochMilli(5_000L))
        // The next launch reads it closed too.
        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)
        assertThat(relaunched.stored(ref)?.issue?.state).isEqualTo(fr.arthurbrugiere.forgeline.core.model.IssueState.CLOSED)

        repository.setOpen(ref, open = true)

        assertThat(repository.cached(ref)?.issue?.state).isEqualTo(fr.arthurbrugiere.forgeline.core.model.IssueState.OPEN)
        assertThat(repository.cached(ref)?.issue?.closedAt).isNull()
    }

    @Test
    fun closing_a_conversation_never_loaded_keeps_nothing() = runTest {
        // An entry without its issue would read as a conversation the forge couldn't serve.
        signIn()

        repository.setOpen(ref, open = false)

        assertThat(repository.cached(ref)).isNull()
    }

    @Test
    fun a_state_change_is_announced_and_a_refused_one_changes_nothing() = runTest {
        signIn()
        api.issues[ref] = issueDetails(ref)
        repository.issue(ref)
        val announced = mutableListOf<IssueRef>()
        val listening = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { repository.changed.collect { announced += it } }

        api.stateFailure = ForgeError.Http(403, "no")
        assertThat(repository.setOpen(ref, open = false)).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "no")))
        assertThat(announced).isEmpty()
        assertThat(repository.cached(ref)?.issue?.state).isEqualTo(fr.arthurbrugiere.forgeline.core.model.IssueState.OPEN)

        api.stateFailure = null
        repository.setOpen(ref, open = false)
        assertThat(announced).containsExactly(ref)
        listening.cancel()
    }

    @Test
    fun what_an_account_may_do_is_what_the_forge_says_and_nothing_signed_out() = runTest {
        api.access[ref.repo] = RepoAccess.ADMIN
        assertThat(repository.access(ref.repo)).isEqualTo(RepoAccess.NONE)
        assertThat(api.calls).isEmpty()

        signIn()

        assertThat(repository.access(ref.repo)).isEqualTo(RepoAccess.ADMIN)
    }

    @Test
    fun a_locked_assigned_conversation_is_kept_as_such_across_launches() = runTest {
        val triaged = issueDetails(ref).copy(
            isLocked = true, assignees = listOf(ForgeUser("me", null, null)), milestone = fr.arthurbrugiere.forgeline.core.model.Milestone(4, "2026.10"),
        )
        api.issues[ref] = triaged
        val event = TimelineItem.Event(fr.arthurbrugiere.forgeline.core.model.ConversationEvent.LOCKED, ForgeUser("maintainer", null, null), null, Instant.parse("2026-10-01T16:44:08Z"))
        val assigned = TimelineItem.Event(fr.arthurbrugiere.forgeline.core.model.ConversationEvent.ASSIGNED, ForgeUser("maintainer", null, null), "me", Instant.parse("2026-10-01T16:45:00Z"))
        api.pages[ref to 1] = TimelinePage(listOf(event, assigned), nextPage = null)
        repository.issue(ref)
        repository.timeline(ref, 1)

        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)

        assertThat(relaunched.stored(ref)?.issue).isEqualTo(triaged)
        assertThat(relaunched.stored(ref)?.firstPage?.items).containsExactly(event, assigned).inOrder()
    }

    @Test
    fun what_can_be_done_to_a_conversation_is_what_its_forge_can_less_what_the_repository_switches_off() = runTest {
        val labels = fr.arthurbrugiere.forgeline.core.model.ConversationAction.LABELS
        val time = fr.arthurbrugiere.forgeline.core.model.ConversationAction.TIME_TRACKING
        api.actions = setOf(labels, time)
        // Signed out, nothing says what the repository switches off: only the forge's abilities are known.
        assertThat(repository.actions(ref.repo)).containsExactly(labels, time)

        signIn()
        api.switchedOff[ref.repo] = setOf(time)

        assertThat(repository.actions(ref.repo)).containsExactly(labels)
        // The role and the settings come in one answer.
        repository.access(ref.repo)
        assertThat(api.calls.count { it.startsWith("access:") }).isEqualTo(1)
    }

    @Test
    fun due_dates_time_and_dependencies_go_to_the_forge_and_are_announced() = runTest {
        signIn()
        val other = IssueRef(ref.repo, 3)
        val announced = mutableListOf<IssueRef>()
        val listening = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { repository.changed.collect { announced += it } }

        repository.setDueDate(ref, java.time.LocalDate.parse("2026-10-10"))
        repository.setTimerRunning(ref, true)
        repository.addTime(ref, 1_800)
        repository.addDependency(ref, other)
        assertThat((repository.dependencies(ref) as ForgeResult.Success).value.map { it.ref }).containsExactly(other)
        assertThat((repository.timeTracking(ref) as ForgeResult.Success).value.totalSeconds).isEqualTo(1_800)
        repository.removeDependency(ref, other)

        assertThat(api.managed).containsExactly(
            "due octo/repo#7: 2026-10-10", "start timer octo/repo#7", "time octo/repo#7: 1800", "depend octo/repo#7 on octo/repo#3", "undepend octo/repo#7 on octo/repo#3",
        ).inOrder()
        // Reading announces nothing.
        assertThat(announced).hasSize(5)
        listening.cancel()
    }

    @Test
    fun a_due_date_survives_a_relaunch() = runTest {
        api.issues[ref] = issueDetails(ref).copy(dueDate = java.time.LocalDate.parse("2026-10-10"))
        repository.issue(ref)

        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)

        assertThat(relaunched.stored(ref)?.issue?.dueDate).isEqualTo(java.time.LocalDate.parse("2026-10-10"))
    }

    @Test
    fun managing_a_conversation_needs_an_account_on_its_forge() = runTest {
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "codeberg-tok")
        val unauthorized = ForgeResult.Failure(ForgeError.Unauthorized)

        assertThat(repository.labels(ref.repo)).isEqualTo(unauthorized)
        assertThat(repository.setLabels(ref, listOf("bug"))).isEqualTo(unauthorized)
        assertThat(repository.assignable(ref.repo)).isEqualTo(unauthorized)
        assertThat(repository.setAssignees(ref, listOf("me"))).isEqualTo(unauthorized)
        assertThat(repository.milestones(ref.repo)).isEqualTo(unauthorized)
        assertThat(repository.setMilestone(ref, null)).isEqualTo(unauthorized)
        assertThat(repository.setLocked(ref, true)).isEqualTo(unauthorized)
        assertThat(repository.isPinned(ref)).isEqualTo(unauthorized)
        assertThat(repository.setPinned(ref, true)).isEqualTo(unauthorized)
        assertThat(repository.transfer(ref, RepoId("octo", "other"))).isEqualTo(unauthorized)
        assertThat(repository.delete(ref)).isEqualTo(unauthorized)
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun every_change_goes_to_the_forge_with_the_account_s_token_and_is_announced() = runTest {
        signIn()
        val announced = mutableListOf<IssueRef>()
        val listening = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { repository.changed.collect { announced += it } }

        repository.setLabels(ref, listOf("bug", "question"))
        repository.setAssignees(ref, listOf("octocat"))
        repository.setMilestone(ref, fr.arthurbrugiere.forgeline.core.model.Milestone(4, "2026.10"))
        repository.setLocked(ref, true)
        repository.setPinned(ref, true)
        repository.setOpen(ref, open = false, reason = fr.arthurbrugiere.forgeline.core.model.CloseReason.NOT_PLANNED)

        assertThat(api.managed).containsExactly(
            "labels octo/repo#7: bug, question", "assignees octo/repo#7: octocat", "milestone octo/repo#7: 2026.10", "lock octo/repo#7", "pin octo/repo#7",
        ).inOrder()
        assertThat(api.stateChanges).containsExactly("octo/repo#7: closed as not_planned")
        assertThat(api.tokens.distinct()).containsExactly("tok")
        assertThat(announced).hasSize(6)
        listening.cancel()
    }

    @Test
    fun the_choices_a_repository_offers_are_read_without_announcing_anything() = runTest {
        signIn()
        val announced = mutableListOf<IssueRef>()
        val listening = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { repository.changed.collect { announced += it } }
        api.pinned += ref

        assertThat((repository.labels(ref.repo) as ForgeResult.Success).value).isEqualTo(api.repoLabels)
        assertThat((repository.assignable(ref.repo) as ForgeResult.Success).value).isEqualTo(api.assignableUsers)
        assertThat((repository.milestones(ref.repo) as ForgeResult.Success).value).isEqualTo(api.repoMilestones)
        assertThat(repository.isPinned(ref)).isEqualTo(ForgeResult.Success(true))
        assertThat(announced).isEmpty()
        listening.cancel()
    }

    @Test
    fun a_refused_change_is_not_announced() = runTest {
        signIn()
        val announced = mutableListOf<IssueRef>()
        val listening = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { repository.changed.collect { announced += it } }
        api.manageFailure = ForgeError.Http(403, "no")

        assertThat(repository.setLabels(ref, listOf("bug"))).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "no")))
        assertThat(repository.delete(ref)).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "no")))
        assertThat(announced).isEmpty()
        listening.cancel()
    }

    @Test
    fun a_transferred_issue_is_no_longer_kept_under_its_old_place() = runTest {
        signIn()
        api.issues[ref] = issueDetails(ref)
        repository.issue(ref)
        api.transferredNumber = 12

        val moved = (repository.transfer(ref, RepoId("octo", "docs")) as ForgeResult.Success).value

        assertThat(moved).isEqualTo(IssueRef(RepoId("octo", "docs"), 12))
        assertThat(repository.cached(ref)).isNull()
        assertThat(DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock).stored(ref)).isNull()
    }

    @Test
    fun a_deleted_issue_is_forgotten_here_too_and_a_refused_deletion_keeps_it() = runTest {
        signIn()
        api.issues[ref] = issueDetails(ref)
        repository.issue(ref)
        api.manageFailure = ForgeError.Http(403, "no")
        repository.delete(ref)
        assertThat(repository.cached(ref)).isNotNull()

        api.manageFailure = null
        assertThat(repository.delete(ref)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(repository.cached(ref)).isNull()
        assertThat(DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock).stored(ref)).isNull()
    }

    @Test
    fun on_gitlab_an_issue_and_a_merge_request_with_one_number_are_kept_apart() = runTest {
        val repo = RepoId("acme", "rocket", ForgeInstance.GitLab)
        val issue = IssueRef(repo, 1, isPullRequest = false)
        val mergeRequest = IssueRef(repo, 1, isPullRequest = true)
        val gitlab = FakeIssueApi().apply {
            issues[issue] = issueDetails(issue, title = "Launch fails")
            issues[mergeRequest] = issueDetails(mergeRequest, title = "Keep install flags")
        }
        val clients = FakeForgeClients().apply { put(ForgeInstance.GitLab, FakeForgeClients(issues = gitlab)) }
        DefaultIssueRepository(clients, accounts, database.conversationDao(), clock).run {
            issue(issue)
            issue(mergeRequest)
        }

        val relaunched = DefaultIssueRepository(clients, accounts, database.conversationDao(), clock)

        assertThat(relaunched.stored(issue)?.issue?.title).isEqualTo("Launch fails")
        assertThat(relaunched.stored(mergeRequest)?.issue?.title).isEqualTo("Keep install flags")
        // One that doesn't say its kind is the issue, as for the forge.
        assertThat(relaunched.stored(IssueRef(repo, 1))?.issue?.title).isEqualTo("Launch fails")
    }

    @Test
    fun where_numbers_are_shared_a_conversation_is_kept_once_whatever_is_known_of_its_kind() = runTest {
        val known = IssueRef(ref.repo, ref.number, isPullRequest = true)
        api.issues[ref] = issueDetails(ref, title = "Fix it")
        repository.issue(known)

        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)

        assertThat(relaunched.stored(ref)?.issue?.title).isEqualTo("Fix it")
        assertThat(relaunched.stored(known)?.issue?.title).isEqualTo("Fix it")
    }

    private suspend fun keep(vararg items: TimelineItem, nextPage: Int? = null) {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(items.toList(), nextPage)
        repository.issue(ref)
        repository.timeline(ref, 1)
    }

    @Test
    fun whose_words_are_one_s_own_is_the_account_of_the_forge() = runTest {
        assertThat(repository.me(ForgeInstance.GitHub)).isNull()
        signIn()

        assertThat(repository.me(ForgeInstance.GitHub)).isEqualTo("me")
        // An account elsewhere says nothing of who writes here.
        assertThat(repository.me(ForgeInstance.Codeberg)).isNull()
    }

    @Test
    fun rewriting_and_deleting_a_comment_and_editing_an_issue_need_an_account_on_the_forge() = runTest {
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "codeberg-tok")

        assertThat(repository.editComment(ref, 1, "New")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(repository.deleteComment(ref, 1)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(repository.edit(ref, "Title", "Text")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.commentChanges).isEmpty()
    }

    @Test
    fun a_rewritten_comment_shows_its_new_text_in_the_conversation_kept() = runTest {
        signIn()
        keep(comment(1, "Hi"), comment(2, "Helo"))

        assertThat(repository.editComment(ref, 2, "Hello")).isEqualTo(ForgeResult.Success(Unit))

        assertThat(api.commentChanges).containsExactly("edit ${ref.repo.fullName}#${ref.number} comment 2: Hello")
        assertThat(api.tokens.last()).isEqualTo("tok")
        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"), comment(2, "Hello")).inOrder()
        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)
        assertThat(relaunched.stored(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"), comment(2, "Hello")).inOrder()
    }

    @Test
    fun a_deleted_comment_leaves_the_conversation_kept_and_its_count() = runTest {
        signIn()
        keep(comment(1, "Hi"), comment(2, "Oops"))

        assertThat(repository.deleteComment(ref, 2)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(api.commentChanges).containsExactly("delete ${ref.repo.fullName}#${ref.number} comment 2")
        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"))
        assertThat(repository.cached(ref)?.issue?.comments).isEqualTo(issueDetails(ref).comments - 1)
    }

    @Test
    fun a_comment_the_forge_keeps_stays_in_the_conversation_kept() = runTest {
        signIn()
        keep(comment(1, "Hi"))
        api.editFailure = ForgeError.Http(403, "not yours")

        assertThat(repository.deleteComment(ref, 1)).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "not yours")))
        assertThat(repository.editComment(ref, 1, "Mine")).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "not yours")))
        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"))
    }

    @Test
    fun rewriting_a_comment_of_a_conversation_not_kept_keeps_nothing() = runTest {
        // An empty entry would read as a conversation the forge couldn't serve.
        signIn()

        repository.editComment(ref, 1, "Hello")
        repository.deleteComment(ref, 1)

        assertThat(repository.cached(ref)).isNull()
    }

    @Test
    fun an_edited_issue_changes_in_the_conversation_kept_and_is_announced() = runTest {
        signIn()
        keep(comment(1, "Hi"))
        val announced = mutableListOf<IssueRef>()
        val listening = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { repository.changed.collect { announced += it } }

        assertThat(repository.edit(ref, "A better title", "")).isEqualTo(ForgeResult.Success(Unit))

        assertThat(api.edited).containsExactly("${ref.repo.fullName}#${ref.number}: A better title / ")
        val kept = repository.cached(ref)?.issue
        assertThat(kept?.title).isEqualTo("A better title")
        // A description emptied is no description, as the forge would answer it.
        assertThat(kept?.body).isNull()
        assertThat(announced).containsExactly(ref)
        listening.cancel()
    }

    @Test
    fun an_edit_the_forge_refuses_changes_nothing_and_announces_nothing() = runTest {
        signIn()
        keep()
        api.editFailure = ForgeError.Network
        val announced = mutableListOf<IssueRef>()
        val listening = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { repository.changed.collect { announced += it } }

        assertThat(repository.edit(ref, "A better title", "Text")).isEqualTo(ForgeResult.Failure(ForgeError.Network))

        assertThat(repository.cached(ref)?.issue?.title).isEqualTo(issueDetails(ref).title)
        assertThat(announced).isEmpty()
        listening.cancel()
    }

    @Test
    fun reacting_needs_an_account_on_the_conversation_s_forge() = runTest {
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "codeberg-tok")

        assertThat(repository.toggleReaction(ref, 1, Reaction.HEART)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun a_reaction_is_given_as_the_account_signed_in_and_shows_on_the_comment_kept() = runTest {
        signIn()
        keep(comment(1, "Hi"), comment(2, "Hello"))
        api.reacted[ref to 2L] = mutableListOf("alice" to Reaction.HEART)

        val counts = (repository.toggleReaction(ref, 2, Reaction.HEART) as ForgeResult.Success).value

        assertThat(counts).containsExactly(Reaction.HEART, 2)
        assertThat(api.reacted[ref to 2L]).containsExactly("alice" to Reaction.HEART, "me" to Reaction.HEART)
        assertThat(api.tokens.last()).isEqualTo("tok")
        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"), comment(2, "Hello").copy(reactions = mapOf(Reaction.HEART to 2))).inOrder()
    }

    @Test
    fun a_reaction_taken_back_leaves_the_conversation_kept() = runTest {
        signIn()
        keep()
        repository.toggleReaction(ref, null, Reaction.ROCKET)
        assertThat(repository.cached(ref)?.issue?.reactions).containsExactly(Reaction.ROCKET, 1)

        repository.toggleReaction(ref, null, Reaction.ROCKET)

        assertThat(repository.cached(ref)?.issue?.reactions).isEmpty()
        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)
        assertThat(relaunched.stored(ref)?.issue?.reactions).isEmpty()
    }

    @Test
    fun a_refused_reaction_changes_nothing_kept_and_reacting_to_nothing_kept_keeps_nothing() = runTest {
        signIn()
        val other = IssueRef(ref.repo, 8)
        repository.toggleReaction(other, 1, Reaction.EYES)
        repository.toggleReaction(other, null, Reaction.EYES)
        assertThat(repository.cached(other)).isNull()

        keep(comment(1, "Hi"))
        api.reactionFailure = ForgeError.Network

        assertThat(repository.toggleReaction(ref, 1, Reaction.EYES)).isEqualTo(ForgeResult.Failure(ForgeError.Network))
        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"))
    }
}
