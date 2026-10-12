package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RepoSettingsTest {
    private val settings = RepoSettings(
        description = "Tools", website = "https://a.example", topics = listOf("kotlin"), defaultBranch = "main",
        switches = mapOf(RepoSwitch.ISSUES to true, RepoSwitch.WIKI to true), membersOnly = setOf(RepoSwitch.WIKI),
        mergeStyle = MergeStyle.MERGE_COMMIT, defaultMerge = MergeMethod.MERGE,
    )

    @Test
    fun a_change_shows_before_the_forge_answers() {
        assertThat(settings.with(RepoSettingsChange.Description("New")).description).isEqualTo("New")
        assertThat(settings.with(RepoSettingsChange.Website("")).website).isEmpty()
        assertThat(settings.with(RepoSettingsChange.Topics(listOf("a", "b"))).topics).containsExactly("a", "b").inOrder()
        assertThat(settings.with(RepoSettingsChange.DefaultBranch("dev")).defaultBranch).isEqualTo("dev")
        assertThat(settings.with(RepoSettingsChange.Style(MergeStyle.FAST_FORWARD)).mergeStyle).isEqualTo(MergeStyle.FAST_FORWARD)
        assertThat(settings.with(RepoSettingsChange.DefaultMerge(MergeMethod.SQUASH)).defaultMerge).isEqualTo(MergeMethod.SQUASH)
        assertThat(settings.with(RepoSettingsChange.Switch(RepoSwitch.ISSUES, false)).switches).containsExactly(RepoSwitch.ISSUES, false, RepoSwitch.WIKI, true)
    }

    @Test
    fun a_feature_switched_by_hand_is_no_longer_for_members_only() {
        val off = settings.with(RepoSettingsChange.Switch(RepoSwitch.WIKI, false))

        assertThat(off.switches[RepoSwitch.WIKI]).isFalse()
        assertThat(off.membersOnly).isEmpty()
    }

    @Test
    fun topics_are_written_the_way_forges_take_them() {
        assertThat(normalizeTopic("  Jetpack Compose ")).isEqualTo("jetpack-compose")
        assertThat(normalizeTopic("material_you")).isEqualTo("material-you")
        assertThat(normalizeTopic("-C++!")).isEqualTo("c")
        assertThat(normalizeTopic(" ! ")).isNull()
        assertThat(normalizeTopic("a".repeat(80))).hasLength(50)
    }

    @Test
    fun switches_say_whether_they_are_about_merging() {
        assertThat(RepoSwitch.entries.filter { !it.merging }).containsExactly(
            RepoSwitch.ISSUES, RepoSwitch.PULL_REQUESTS, RepoSwitch.WIKI, RepoSwitch.DISCUSSIONS, RepoSwitch.PROJECTS, RepoSwitch.CI,
            RepoSwitch.RELEASES, RepoSwitch.PACKAGES, RepoSwitch.TEMPLATE,
        )
    }

    @Test
    fun who_sees_it_and_whether_it_is_archived_show_at_once_too() {
        assertThat(settings.with(RepoSettingsChange.Visibility(RepoVisibility.PRIVATE)).visibility).isEqualTo(RepoVisibility.PRIVATE)
        assertThat(settings.with(RepoSettingsChange.Archived(true)).archived).isTrue()
        // A forge offers public or private unless it says otherwise.
        assertThat(settings.visibilities).containsExactly(RepoVisibility.PUBLIC, RepoVisibility.PRIVATE).inOrder()
    }
}
