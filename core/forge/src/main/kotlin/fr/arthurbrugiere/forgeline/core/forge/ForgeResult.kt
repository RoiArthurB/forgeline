package fr.arthurbrugiere.forgeline.core.forge

sealed interface ForgeResult<out T> {
    data class Success<T>(val value: T) : ForgeResult<T>

    data class Failure(val error: ForgeError) : ForgeResult<Nothing>
}

/** The value [transform]ed when there is one; a failure stays as it is. */
inline fun <T, R> ForgeResult<T>.map(transform: (T) -> R): ForgeResult<R> = when (this) {
    is ForgeResult.Failure -> this
    is ForgeResult.Success -> ForgeResult.Success(transform(value))
}

sealed interface ForgeError {
    data object Network : ForgeError

    data object Unauthorized : ForgeError

    data class RateLimited(val resetAtEpochSeconds: Long?) : ForgeError

    data class Http(val status: Int, val message: String?) : ForgeError

    /** The forge answered, but not with what its API promises: a page instead of data, a field missing, a date that isn't one. */
    data object Unreadable : ForgeError

    /** The forge has no API for this (Actions on a Forgejo instance, for example). */
    data object Unsupported : ForgeError
}
