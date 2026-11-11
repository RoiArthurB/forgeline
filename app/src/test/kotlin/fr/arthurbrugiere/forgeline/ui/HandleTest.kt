package fr.arthurbrugiere.forgeline.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HandleTest {
    @Test
    fun a_login_reads_with_an_at_unless_it_carries_its_own_mark() {
        assertThat(handleOf("octocat")).isEqualTo("@octocat")
        // Regression: SourceHut's read "@~sircmpwn".
        assertThat(handleOf("~sircmpwn")).isEqualTo("~sircmpwn")
        assertThat(handleOf("alice.bsky.social")).isEqualTo("@alice.bsky.social")
    }
}
