package fr.arthurbrugiere.forgeline.ui

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.PHONE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class AvatarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun initialOverflows(size: Dp, login: String): Boolean {
        composeRule.setContent { Avatar(url = null, login = login, size = size) }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(login.take(1).uppercase())
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().didOverflowHeight
    }

    @Test
    fun the_initial_fits_inside_a_small_avatar() {
        // Regression: the fallback initial used a fixed text size and overflowed 20dp avatars.
        assertWithMessage("initial overflows a 20dp avatar").that(initialOverflows(20.dp, "cryppadotta")).isFalse()
    }

    @Test
    fun the_initial_fits_inside_a_large_avatar() {
        assertWithMessage("initial overflows a 56dp avatar").that(initialOverflows(56.dp, "octocat")).isFalse()
    }

    @OptIn(DelicateCoilApi::class)
    @Test
    fun the_initial_goes_away_once_the_picture_loads() {
        // Regression: the initial stayed behind the image and showed through transparent profile pictures.
        val loadEverything = Interceptor { chain ->
            SuccessResult(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImage(), chain.request, DataSource.MEMORY)
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ApplicationProvider.getApplicationContext()).components { add(loadEverything) }.build())
        try {
            composeRule.setContent { Avatar(url = "https://example.com/octocat.png", login = "octocat", size = 56.dp) }

            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("O").fetchSemanticsNodes().isEmpty() }
        } finally {
            SingletonImageLoader.reset()
        }
    }
}
