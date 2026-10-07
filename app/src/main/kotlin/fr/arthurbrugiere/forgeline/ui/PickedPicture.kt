package fr.arthurbrugiere.forgeline.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

/** A picture the reader chose to attach. [bytes] is null when it is larger than a forge takes. */
class PickedPicture(val name: String, val mimeType: String, val bytes: ByteArray?)

/** What a forge takes in one file, give or take: gitlab.com and Codeberg both refuse more. */
const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

/**
 * Reads the picture at [uri], as the system's picker hands it over. Null when it can't be read. No more than [maxBytes]
 * are ever held: a longer file comes back without its bytes. To be called off the main thread.
 */
fun readPicture(resolver: ContentResolver, uri: Uri, maxBytes: Int = MAX_ATTACHMENT_BYTES): PickedPicture? = runCatching {
    val mimeType = resolver.getType(uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
    val named = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    // The picker names some pictures by a number alone: the forge tells what a file is by its extension.
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
    val name = (named?.takeIf { it.isNotBlank() } ?: "image").let { if ('.' in it || extension == null) it else "$it.$extension" }
    val input = resolver.openInputStream(uri) ?: return@runCatching null
    val read = input.use { stream ->
        // One byte past the limit is enough to know it is too long.
        val kept = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (kept.size() <= maxBytes) {
            val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - kept.size()))
            if (count < 0) break
            kept.write(buffer, 0, count)
        }
        kept.toByteArray()
    }
    PickedPicture(name, mimeType, read.takeIf { it.size <= maxBytes })
}.getOrNull()
