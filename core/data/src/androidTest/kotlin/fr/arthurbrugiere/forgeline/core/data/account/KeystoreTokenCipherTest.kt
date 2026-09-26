package fr.arthurbrugiere.forgeline.core.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a real Android system: the JVM has no Android Keystore. */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenCipherTest {
    private val cipher = KeystoreTokenCipher(keyAlias = "forgeline_test_key")

    @Test
    fun round_trips_a_token() {
        val encrypted = cipher.encrypt("ghp_secret_token")

        assertFalse(encrypted.contains("ghp_secret_token"))
        assertEquals("ghp_secret_token", cipher.decrypt(encrypted))
    }

    @Test
    fun uses_a_fresh_iv_for_every_encryption() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }
}
