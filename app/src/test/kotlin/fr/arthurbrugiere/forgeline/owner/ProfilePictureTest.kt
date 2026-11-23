package fr.arthurbrugiere.forgeline.owner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePictureTest {
    private fun png(width: Int, height: Int, left: Int = Color.RED, right: Int = Color.BLUE): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, if (x < width / 2) left else right)
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    @Test
    fun a_wide_photo_becomes_a_small_square_cut_from_its_middle() {
        val prepared = ProfilePicture.prepare(png(1600, 800))!!
        val decoded = BitmapFactory.decodeByteArray(prepared, 0, prepared.size)

        assertThat(decoded.width).isEqualTo(ProfilePicture.SIZE)
        assertThat(decoded.height).isEqualTo(ProfilePicture.SIZE)
        // Cut from the middle: both halves of the picture show in the square.
        assertThat(Color.red(decoded.getPixel(10, 256))).isGreaterThan(200)
        assertThat(Color.blue(decoded.getPixel(500, 256))).isGreaterThan(200)
        // A JPEG, well under GitLab's 200 KB.
        assertThat(prepared.take(2)).containsExactly(0xFF.toByte(), 0xD8.toByte()).inOrder()
        assertThat(prepared.size).isLessThan(200_000)
    }

    @Test
    fun a_small_picture_is_squared_and_never_enlarged() {
        val decoded = ProfilePicture.prepare(png(100, 300))!!.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

        assertThat(decoded.width).isEqualTo(100)
        assertThat(decoded.height).isEqualTo(100)
    }

    @Test
    fun what_isn_t_a_picture_is_nothing() {
        assertThat(ProfilePicture.prepare("not a picture".toByteArray())).isNull()
    }
}
