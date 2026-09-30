package fr.arthurbrugiere.forgeline.core.forge

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A PKCE pair (RFC 7636): the verifier stays on the phone, the challenge goes to the forge. */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        fun generate(random: SecureRandom = SecureRandom()): Pkce = of(encoder.encodeToString(ByteArray(48).also(random::nextBytes)))

        /** The S256 challenge for [verifier]. */
        fun of(verifier: String): Pkce =
            Pkce(verifier, encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))))
    }
}
