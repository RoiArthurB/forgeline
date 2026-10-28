package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class FirstIssueLabelsTest {
    @Test
    fun the_names_projects_give_a_first_issue_all_match() {
        listOf(
            "good first issue", "Good First Issue", "good-first-issue", "Kind/Good First Issue", "first-timers-only",
            "quick win::first-time contribution", "beginner", "beginner-friendly", "Newcomers", "easy-fix", "easy", "E-easy pick", "low-hanging-fruit",
        ).forEach { assertWithMessage(it).that(FirstIssueLabels.matches(it)).isTrue() }
    }

    @Test
    fun labels_that_only_look_alike_do_not() {
        listOf("bug", "help wanted", "easy-to-reproduce", "first", "good", "needs-triage", "Seeking community contributions")
            .forEach { assertWithMessage(it).that(FirstIssueLabels.matches(it)).isFalse() }
    }

    @Test
    fun every_name_github_is_asked_for_matches_too() {
        assertThat(FirstIssueLabels.searched.filterNot(FirstIssueLabels::matches)).isEmpty()
    }
}
