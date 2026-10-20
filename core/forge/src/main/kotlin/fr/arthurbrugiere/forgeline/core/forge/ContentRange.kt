package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.LogPart

/**
 * What a forge answered a request for part of a log with: the part (206), nothing past its end (416), or Unsupported
 * when it sent the whole log (200), which is then left unread. Null for any other answer: the forge's own error.
 */
suspend fun logPartOf(status: Int, contentRange: String?, body: suspend () -> ByteArray): ForgeResult<LogPart>? = when (status) {
    206 -> ContentRange.parse(contentRange)?.let { range ->
        range.sent?.let { ForgeResult.Success(LogPart(body(), it.first, range.total)) }
    } ?: ForgeResult.Failure(ForgeError.Unreadable)
    416 -> ContentRange.parse(contentRange)?.let { ForgeResult.Success(LogPart(ByteArray(0), it.total, it.total)) }
        ?: ForgeResult.Failure(ForgeError.Unreadable)
    200 -> ForgeResult.Failure(ForgeError.Unsupported)
    else -> null
}

/**
 * What a `Content-Range` header says: the bytes sent ([sent], null when none were, as a 416 answers) out of the
 * [total] there are. Null when it says something else (an unknown total, another unit).
 */
data class ContentRange(val sent: LongRange?, val total: Long) {
    companion object {
        private val pattern = Regex("""^bytes (?:(\d+)-(\d+)|\*)/(\d+)$""")

        fun parse(header: String?): ContentRange? {
            val match = pattern.matchEntire(header?.trim() ?: return null) ?: return null
            val (first, last, total) = match.destructured
            val sent = if (first.isEmpty()) null else first.toLong()..last.toLong()
            return ContentRange(sent, total.toLong())
        }
    }
}
