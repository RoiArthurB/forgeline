package fr.arthurbrugiere.forgeline.owner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * A picture picked for a profile, made what every forge takes: square (cut from its middle), [SIZE] pixels at most a
 * side, as a JPEG. GitLab takes 200 KB at most and Tangled a megabyte; this stays far under both.
 */
internal object ProfilePicture {
    const val SIZE = 512
    private const val QUALITY = 90

    fun read(context: Context, uri: Uri): ByteArray? = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let(::prepare)

    /** Null for what isn't a picture. */
    fun prepare(bytes: ByteArray): ByteArray? {
        // Decoded at about twice the size wanted at most: a phone's photo is tens of megapixels.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = min(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val sized = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
        return ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
    }
}
