package fr.arthurbrugiere.forgeline.core.data.draft

import org.junit.After
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreDraftStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val clock = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val file: File by lazy { tmp.newFile("drafts.preferences_pb").also { it.delete() } }
    private var disk: DataStore<Preferences>? = null

    // The store saves in the background, which a test's own background scope never lets it do while the test only
    // advances time: this scope's work counts as the test's, and ends with it.
    private var saving: CoroutineScope? = null

    private fun TestScope.saving(): CoroutineScope = saving ?: CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { saving = it }

    @After
    fun stopSaving() {
        saving?.cancel()
    }

    /** One file for the whole test, as on a phone: a store made again reads what the one before wrote. */
    private fun TestScope.dataStore(): DataStore<Preferences> =
        disk ?: PreferenceDataStoreFactory.create(scope = saving()) { file }.also { disk = it }

    private fun TestScope.store() = DataStoreDraftStore(dataStore(), saving(), clock)

    private val conversation = IssueRef(RepoId("octo", "repo"), 7)
    private val comment = commentDraftKey(conversation)
    private val issue = issueDraftKey(conversation.repo)

    @Test
    fun there_is_no_draft_where_nothing_was_written() = runTest {
        val store = store()

        assertThat(store.peek(comment)).isNull()
        assertThat(store.read(comment)).isNull()
    }

    @Test
    fun a_draft_is_there_at_once_for_whoever_wrote_it() = runTest {
        val store = store()

        store.write(comment, Draft(body = "Half a thought"))

        // Before anything reached the disk.
        assertThat(store.peek(comment)).isEqualTo(Draft(body = "Half a thought"))
    }

    @Test
    fun a_draft_is_there_again_after_the_app_was_closed() = runTest {
        store().apply {
            write(comment, Draft(body = "Half a thought"))
            write(issue, Draft("Crash", "Steps"))
        }
        advanceUntilIdle()

        val relaunched = store()

        // Not known before it is read from disk, then known at once.
        assertThat(relaunched.peek(comment)).isNull()
        assertThat(relaunched.read(comment)).isEqualTo(Draft(body = "Half a thought"))
        assertThat(relaunched.peek(comment)).isEqualTo(Draft(body = "Half a thought"))
        assertThat(relaunched.read(issue)).isEqualTo(Draft("Crash", "Steps"))
    }

    @Test
    fun text_typed_fast_is_saved_as_its_last_state_never_an_older_one() = runTest {
        val store = store()

        "Half a thought".indices.forEach { store.write(comment, Draft(body = "Half a thought".take(it + 1))) }
        advanceUntilIdle()

        assertThat(store().read(comment)).isEqualTo(Draft(body = "Half a thought"))
    }

    @Test
    fun a_draft_emptied_or_sent_is_gone_from_the_disk_too() = runTest {
        val store = store()
        store.write(comment, Draft(body = "Half a thought"))
        store.write(issue, Draft("Crash", "Steps"))
        advanceUntilIdle()

        store.write(comment, Draft())
        store.write(issue, null)
        advanceUntilIdle()

        assertThat(store.peek(comment)).isNull()
        assertThat(store.read(comment)).isNull()
        assertThat(dataStore().data.first().asMap()).isEmpty()
    }

    @Test
    fun a_draft_written_while_the_disk_is_read_is_the_one_that_counts() = runTest {
        store().write(comment, Draft(body = "Old"))
        advanceUntilIdle()
        val relaunched = store()

        relaunched.write(comment, Draft(body = "New"))

        assertThat(relaunched.read(comment)).isEqualTo(Draft(body = "New"))
    }

    @Test
    fun drafts_of_a_forge_are_forgotten_together_and_the_others_kept() = runTest {
        val elsewhere = commentDraftKey(IssueRef(RepoId("octo", "repo", ForgeInstance.Codeberg), 7))
        val store = store()
        store.write(comment, Draft(body = "On GitHub"))
        store.write(issue, Draft("Crash", "On GitHub"))
        store.write(elsewhere, Draft(body = "On Codeberg"))
        advanceUntilIdle()

        store.forget(ForgeInstance.GitHub)
        advanceUntilIdle()

        assertThat(store.peek(comment)).isNull()
        assertThat(store.peek(issue)).isNull()
        assertThat(store.peek(elsewhere)).isEqualTo(Draft(body = "On Codeberg"))
        val relaunched = store()
        assertThat(relaunched.read(comment)).isNull()
        assertThat(relaunched.read(issue)).isNull()
        assertThat(relaunched.read(elsewhere)).isEqualTo(Draft(body = "On Codeberg"))
    }

    @Test
    fun drafts_never_sent_do_not_pile_up_for_good() = runTest {
        val store = store()
        val keys = (1..DataStoreDraftStore.MAX_DRAFTS + 3).map { commentDraftKey(IssueRef(conversation.repo, it)) }

        keys.forEach { key ->
            now += 1_000
            store.write(key, Draft(body = "Draft"))
            advanceUntilIdle()
        }

        // The ones written longest ago gave way, here and on disk.
        assertThat(dataStore().data.first().asMap()).hasSize(DataStoreDraftStore.MAX_DRAFTS)
        val relaunched = store()
        assertThat(relaunched.read(keys.first())).isNull()
        assertThat(relaunched.read(keys[2])).isNull()
        assertThat(relaunched.read(keys[3])).isEqualTo(Draft(body = "Draft"))
        assertThat(relaunched.read(keys.last())).isEqualTo(Draft(body = "Draft"))
        assertThat(store.peek(keys.first())).isNull()
    }

    @Test
    fun a_draft_the_app_cannot_read_is_no_draft() = runTest {
        // Written by another version, or damaged: nothing to offer, and nothing to crash on.
        dataStore().edit { it[stringPreferencesKey(comment)] = "not a draft" }

        assertThat(store().read(comment)).isNull()
    }

    @Test
    fun keys_tell_conversations_kinds_and_forges_apart() {
        val gitlab = RepoId("Octo", "Repo", ForgeInstance.GitLab)

        // GitLab numbers merge requests apart from issues: #7 and !7 are two conversations.
        assertThat(commentDraftKey(IssueRef(gitlab, 7, isPullRequest = true))).isNotEqualTo(commentDraftKey(IssueRef(gitlab, 7, isPullRequest = false)))
        assertThat(commentDraftKey(IssueRef(gitlab, 7))).isEqualTo(commentDraftKey(IssueRef(gitlab, 7, isPullRequest = false)))
        // Elsewhere the number alone tells: knowing the kind or not is the same conversation.
        assertThat(commentDraftKey(conversation.copy(isPullRequest = true))).isEqualTo(comment)
        // A repository is the same however its name is cased.
        assertThat(issueDraftKey(RepoId("OCTO", "Repo"))).isEqualTo(issue)
        assertThat(issueDraftKey(RepoId("octo", "repo", ForgeInstance.Codeberg))).isNotEqualTo(issue)
        // An issue being written is not a comment being written.
        assertThat(issue).isNotEqualTo(comment)
    }

    @Test
    fun the_store_kept_in_memory_behaves_the_same_while_it_lives() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryDraftStore()

        store.write(comment, Draft(body = "Half a thought"))
        store.write(issue, Draft("Crash", ""))
        assertThat(store.read(comment)).isEqualTo(Draft(body = "Half a thought"))
        store.write(comment, Draft())
        assertThat(store.peek(comment)).isNull()
        store.forget(ForgeInstance.GitHub)
        assertThat(store.peek(issue)).isNull()
    }
}
