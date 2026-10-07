package fr.arthurbrugiere.forgeline.ui

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class PickedPictureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

    /** A picture as the picker hands it over: an address that says nothing, its bytes behind it. */
    private fun picked(address: String, bytes: ByteArray): Uri = Uri.parse(address).also { uri ->
        shadowOf(resolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
    }

    @Test
    fun a_picture_is_read_whole_with_a_name_a_forge_can_tell_its_kind_from() {
        shadowOf(android.webkit.MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("png", "image/png")
        val uri = picked("file:///pictures/shot.png", byteArrayOf(1, 2, 3))

        val picture = readPicture(resolver, uri)!!

        assertThat(picture.name).isEqualTo("shot.png")
        assertThat(picture.mimeType).isEqualTo("image/png")
        assertThat(picture.bytes).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun a_picture_exactly_as_large_as_allowed_is_kept_and_one_byte_more_is_not() {
        val allowed = readPicture(resolver, picked("file:///pictures/a.png", ByteArray(8)), maxBytes = 8)!!
        val tooLarge = readPicture(resolver, picked("file:///pictures/b.png", ByteArray(9)), maxBytes = 8)!!

        assertThat(allowed.bytes).hasLength(8)
        // Named still, so it can be said which one; its bytes are not held.
        assertThat(tooLarge.bytes).isNull()
        assertThat(tooLarge.name).isEqualTo("b.png")
    }

    @Test
    fun a_picture_far_larger_than_allowed_is_not_read_to_its_end() {
        var read = 0
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0.also { read++ }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length.also { read += it }
        }
        val uri = Uri.parse("file:///pictures/endless.png")
        shadowOf(resolver).registerInputStreamSupplier(uri) { endless }

        val picture = readPicture(resolver, uri, maxBytes = 1_000)!!

        assertThat(picture.bytes).isNull()
        assertThat(read).isEqualTo(1_001)
    }

    @Test
    fun a_picture_of_no_known_kind_is_still_sent_as_a_file() {
        val picture = readPicture(resolver, picked("file:///pictures/thing", byteArrayOf(1)))!!

        // Its name is its own, with at most the extension the system gives a file of no known kind.
        assertThat(picture.name).startsWith("thing")
        assertThat(picture.mimeType).isEqualTo("application/octet-stream")
    }

    @Test
    fun a_picture_that_is_not_there_is_none() {
        val gone = Uri.fromFile(java.io.File(tmp.root, "gone.png"))

        assertThat(readPicture(resolver, gone)).isNull()
    }
}
