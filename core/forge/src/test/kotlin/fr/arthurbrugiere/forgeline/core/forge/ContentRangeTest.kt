package fr.arthurbrugiere.forgeline.core.forge

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContentRangeTest {
    @Test
    fun a_part_says_its_bytes_and_the_whole_size() {
        // As GitHub's log storage answered on 2026-10-17.
        assertThat(ContentRange.parse("bytes 40000-40999/86345")).isEqualTo(ContentRange(40000L..40999L, 86345))
    }

    @Test
    fun a_range_past_the_end_says_only_the_size() {
        assertThat(ContentRange.parse("bytes */86345")).isEqualTo(ContentRange(null, 86345))
    }

    @Test
    fun a_part_is_read_a_whole_log_is_left_unread() = runTest {
        val part = logPartOf(206, "bytes 10-12/40") { "abc".toByteArray() }
        assertThat((part as ForgeResult.Success).value.run { Triple(String(bytes), start, total) }).isEqualTo(Triple("abc", 10L, 40L))

        var read = false
        assertThat(logPartOf(200, null) { read = true; ByteArray(0) }).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(read).isFalse()
    }

    @Test
    fun past_the_end_is_an_empty_part_and_other_answers_are_the_forge_s_to_say() = runTest {
        val end = logPartOf(416, "bytes */40") { error("no body") } as ForgeResult.Success
        assertThat(end.value.run { Triple(bytes.size, start, total) }).isEqualTo(Triple(0, 40L, 40L))
        assertThat(logPartOf(404, null) { ByteArray(0) }).isNull()
        assertThat(logPartOf(206, null) { ByteArray(0) }).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    @Test
    fun an_unknown_size_or_another_unit_says_nothing() {
        assertThat(ContentRange.parse("bytes 0-99/*")).isNull()
        assertThat(ContentRange.parse("items 0-9/10")).isNull()
        assertThat(ContentRange.parse(null)).isNull()
    }
}
