package fr.arthurbrugiere.forgeline.owner

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.core.testing.FakeOwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.testing.FakeOwnerSettingsRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.organizationSettings
import fr.arthurbrugiere.forgeline.core.testing.ownerSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeOwnerSettingsApi()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun viewModel(login: String = "mona", organization: Boolean = false) =
        OwnerSettingsViewModel(ForgeInstance.GitHub, login, organization, FakeOwnerSettingsRepository(api = api))

    @Test
    fun one_s_own_profile_and_an_organisation_s_are_read_apart() = test {
        val mine = viewModel()
        val octo = viewModel("octo", organization = true)
        advanceUntilIdle()

        assertThat(mine.state.value.owner).isEqualTo(Owner.Me)
        assertThat(mine.state.value.settings).isEqualTo(ownerSettings())
        assertThat(octo.state.value.owner).isEqualTo(Owner.Organization("octo"))
        assertThat(octo.state.value.settings).isEqualTo(organizationSettings())
    }

    @Test
    fun a_change_shows_at_once_and_a_switch_can_be_taken_back() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.change(OwnerSettingsChange.Switch(OwnerSwitch.HIREABLE, true))
        assertThat(viewModel.state.value.settings?.switches?.get(OwnerSwitch.HIREABLE)).isTrue()
        advanceUntilIdle()
        assertThat(viewModel.state.value.applied?.undo).isEqualTo(OwnerSettingsChange.Switch(OwnerSwitch.HIREABLE, false))

        viewModel.undo()
        advanceUntilIdle()
        assertThat(api.changes.last()).isEqualTo(OwnerSettingsChange.Switch(OwnerSwitch.HIREABLE, false))
        // No way back from the way back.
        assertThat(viewModel.state.value.applied?.undo).isNull()
    }

    @Test
    fun words_have_no_way_back_and_a_choice_does() = test {
        val viewModel = viewModel("octo", organization = true)
        advanceUntilIdle()

        viewModel.change(OwnerSettingsChange.Text(OwnerText.BIO, "Tools"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.applied?.undo).isNull()
        viewModel.change(OwnerSettingsChange.Choice(OwnerChoice.DEFAULT_REPO_PERMISSION, "write"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.applied?.undo).isEqualTo(OwnerSettingsChange.Choice(OwnerChoice.DEFAULT_REPO_PERMISSION, "read"))
    }

    @Test
    fun a_refused_change_is_taken_back_and_said() = test {
        val viewModel = viewModel()
        advanceUntilIdle()
        api.writeFailure = ForgeError.Unauthorized

        viewModel.change(OwnerSettingsChange.Text(OwnerText.LOCATION, "Lyon"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.settings?.texts?.get(OwnerText.LOCATION)).isEqualTo("Paris")
        assertThat(viewModel.state.value.refused).isEqualTo(ForgeError.Unauthorized)
    }

    @Test
    fun what_changes_nothing_isn_t_sent() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.change(OwnerSettingsChange.Text(OwnerText.LOCATION, "Paris"))
        advanceUntilIdle()

        assertThat(api.changes).isEmpty()
    }

    @Test
    fun who_is_blocked_is_read_when_asked_and_blocking_shows_at_once() = test {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.blocksSupported).isTrue()
        assertThat(viewModel.state.value.blocked).isEqualTo(fr.arthurbrugiere.forgeline.repo.Loadable.Idle)

        viewModel.loadBlocked()
        advanceUntilIdle()
        viewModel.setBlocked("@troll", blocked = true)
        viewModel.setBlocked("spammer", blocked = false)

        val shown = (viewModel.state.value.blocked as fr.arthurbrugiere.forgeline.repo.Loadable.Loaded).value.map { it.login }
        assertThat(shown).containsExactly("troll")
        advanceUntilIdle()
        assertThat(api.calls).containsAtLeast("block:me:troll", "unblock:me:spammer").inOrder()
    }

    @Test
    fun a_refused_block_is_taken_back() = test {
        val viewModel = viewModel()
        viewModel.loadBlocked()
        advanceUntilIdle()
        api.writeFailure = ForgeError.Unauthorized

        viewModel.setBlocked("troll", blocked = true)
        advanceUntilIdle()

        assertThat((viewModel.state.value.blocked as fr.arthurbrugiere.forgeline.repo.Loadable.Loaded).value.map { it.login }).containsExactly("spammer")
        assertThat(viewModel.state.value.refused).isEqualTo(ForgeError.Unauthorized)
    }

    @Test
    fun an_organisation_s_members_are_read_when_asked_and_read_again_after_a_change() = test {
        val viewModel = viewModel("octo", organization = true)
        advanceUntilIdle()
        assertThat(viewModel.state.value.membersSupported).isTrue()

        viewModel.loadMembers()
        advanceUntilIdle()
        viewModel.changeMembers(fr.arthurbrugiere.forgeline.core.data.user.MemberChange.Role("hubot", "admin"))
        advanceUntilIdle()

        val members = (viewModel.state.value.members as fr.arthurbrugiere.forgeline.repo.Loadable.Loaded).value
        assertThat(members.members.single { it.login == "hubot" }.role).isEqualTo("admin")
        assertThat(api.calls).containsAtLeast("members:octo", "role:octo:hubot:admin", "members:octo").inOrder()
    }

    @Test
    fun a_refused_member_change_is_said_and_the_list_stays() = test {
        val viewModel = viewModel("octo", organization = true)
        viewModel.loadMembers()
        advanceUntilIdle()
        api.writeFailure = ForgeError.Http(422, "Can't remove the last owner")

        viewModel.changeMembers(fr.arthurbrugiere.forgeline.core.data.user.MemberChange.Remove("mona"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.refused).isEqualTo(ForgeError.Http(422, "Can't remove the last owner"))
        assertThat((viewModel.state.value.members as fr.arthurbrugiere.forgeline.repo.Loadable.Loaded).value.members.map { it.login }).contains("mona")
    }

    @Test
    fun one_s_own_profile_has_no_members() = test {
        assertThat(viewModel().state.value.membersSupported).isFalse()
    }
}
