package fr.arthurbrugiere.forgeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The stores' icon (F-Droid can't draw the launcher's vector icon) is the launcher icon drawn at 512 px: the 72dp a
 * launcher shows of the adaptive icon's 108dp, as a full square the store rounds itself. To redraw it after the
 * launcher icon changes: `FORGELINE_RECORD_STORE_ICON=1 ./gradlew :app:testDebugUnitTest --tests '*StoreIconTest' --rerun`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreIconTest {
    private val icon = File("../fastlane/metadata/android/en-US/images/icon.png")

    @Test
    fun the_store_icon_is_the_launcher_icon() {
        val drawn = launcherIcon()
        if (System.getenv("FORGELINE_RECORD_STORE_ICON") == "1") {
            icon.outputStream().use { drawn.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertWithMessage("${icon.path} is missing: run this test with FORGELINE_RECORD_STORE_ICON=1").that(icon.isFile).isTrue()
        val stored = BitmapFactory.decodeFile(icon.path)
        assertThat(stored.width to stored.height).isEqualTo(SIZE to SIZE)
        // Anti-aliasing may differ by a shade between machines: the icon must match, not every edge pixel.
        var off = 0
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val a = stored.getPixel(x, y)
            val b = drawn.getPixel(x, y)
            val gap = maxOf(abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)), abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)), abs((a and 0xFF) - (b and 0xFF)))
            if (gap > 24) off++
        }
        assertWithMessage("pixels unlike the launcher icon").that(off).isLessThan(SIZE * SIZE / 200)
    }

    private fun launcherIcon(): Bitmap {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.launcher_ink))
        // The foreground spans 108dp, of which the middle 72dp fill the icon.
        val bleed = SIZE * 18 / 72
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!.apply {
            setBounds(-bleed, -bleed, SIZE + bleed, SIZE + bleed)
            draw(canvas)
        }
        return bitmap
    }

    private companion object {
        const val SIZE = 512
    }
}
