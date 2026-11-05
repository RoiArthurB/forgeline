package fr.arthurbrugiere.forgeline.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.TextSize
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AppTextSizeTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** How tall [sp] of text comes out, in dp, at [size] and then at the phone's own size. */
    private fun measured(size: TextSize, sp: Float): Pair<Dp, Dp> {
        var app = Dp.Unspecified
        var phone = Dp.Unspecified
        composeRule.setContent {
            phone = with(LocalDensity.current) { sp.sp.toDp() }
            AppTextSize(size) { app = with(LocalDensity.current) { sp.sp.toDp() } }
        }
        composeRule.waitForIdle()
        return app to phone
    }

    @Test
    fun the_phone_s_size_leaves_text_as_it_is() {
        val (app, phone) = measured(TextSize.PHONE, 15f)
        assertThat(app).isEqualTo(phone)
    }

    @Test
    fun a_larger_size_grows_the_app_s_text_by_its_share() {
        val (app, _) = measured(TextSize.LARGER, 20f)
        assertThat(app.value).isWithin(0.01f).of(23f)
    }

    @Test
    fun a_smaller_size_shrinks_it() {
        val (app, phone) = measured(TextSize.SMALLER, 20f)
        assertThat(app.value).isWithin(0.01f).of(18f)
        assertThat(app).isLessThan(phone)
    }

    @Test
    @Config(fontScale = 2.0f)
    fun on_a_phone_set_to_large_text_the_phone_s_own_curve_still_applies() {
        // The phone grows big text less than small: the app's larger size is that curve read 1.3 times further on.
        var app = Dp.Unspecified
        var curve = Dp.Unspecified
        composeRule.setContent {
            curve = with(LocalDensity.current) { 26.sp.toDp() }
            AppTextSize(TextSize.LARGEST) { app = with(LocalDensity.current) { 20.sp.toDp() } }
        }
        composeRule.waitForIdle()
        assertThat(app.value).isWithin(0.01f).of(curve.value)
    }
}
