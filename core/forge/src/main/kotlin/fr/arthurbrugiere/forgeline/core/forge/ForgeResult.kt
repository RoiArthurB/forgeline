package fr.arthurbrugiere.forgeline.core.forge

sealed interface ForgeResult<out T> {
    data class Success<T>(val value: T) : ForgeResult<T>

    data class Failure(val error: ForgeError) : ForgeResult<Nothing>
}

sealed interface ForgeError {
    data object Network : ForgeError

    data object Unauthorized : ForgeError

    data class RateLimited(val resetAtEpochSeconds: Long?) : ForgeError

    data class Http(val status: Int, val message: String?) : ForgeError

    /** The forge has no API for this (Actions on a Forgejo instance, for example). */
    data object Unsupported : ForgeError
}
