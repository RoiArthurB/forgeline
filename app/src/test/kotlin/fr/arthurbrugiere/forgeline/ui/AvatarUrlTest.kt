package fr.arthurbrugiere.forgeline.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AvatarUrlTest {
    @Test
    fun a_github_avatar_is_asked_at_the_size_it_shows() {
        // Unsized, GitHub sends 460 px: 12 to 26 KB each where 2 to 5 KB do (measured 2026-10-01).
        assertThat(sizedAvatarUrl("https://avatars.githubusercontent.com/u/9919?v=4", 96))
            .isEqualTo("https://avatars.githubusercontent.com/u/9919?v=4&s=96")
        assertThat(sizedAvatarUrl("https://avatars.githubusercontent.com/u/9919", 96))
            .isEqualTo("https://avatars.githubusercontent.com/u/9919?s=96")
    }

    @Test
    fun sizes_are_rounded_up_to_a_few_steps_so_one_download_serves_nearby_sizes() {
        fun asked(pixels: Int) = sizedAvatarUrl("https://avatars.githubusercontent.com/u/9919?v=4", pixels).substringAfter("&s=")

        assertThat(listOf(40, 48, 49, 96, 100, 144, 200, 300).map(::asked))
            .containsExactly("48", "48", "96", "96", "144", "144", "288", "460").inOrder()
    }

    @Test
    fun the_largest_avatars_keep_the_full_picture() {
        assertThat(sizedAvatarUrl("https://avatars.githubusercontent.com/u/9919?v=4", 900))
            .isEqualTo("https://avatars.githubusercontent.com/u/9919?v=4&s=460")
    }

    @Test
    fun an_avatar_already_sized_is_left_alone() {
        val url = "https://avatars.githubusercontent.com/u/9919?s=40&v=4"

        assertThat(sizedAvatarUrl(url, 96)).isEqualTo(url)
    }

    @Test
    fun other_forges_avatars_are_left_alone() {
        // Codeberg answers 404 to a size parameter (checked 2026-10-01).
        val codeberg = "https://codeberg.org/avatars/dae8ab126a96f6fbd6942cf08ab92382"

        assertThat(sizedAvatarUrl(codeberg, 96)).isEqualTo(codeberg)
        assertThat(sizedAvatarUrl("not a url", 96)).isEqualTo("not a url")
    }
}
