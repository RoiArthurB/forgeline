package fr.arthurbrugiere.forgeline.core.data.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

interface TokenCipher {
    fun encrypt(plaintext: String): String

    /** @throws java.security.GeneralSecurityException when the key is gone or the data was tampered with. */
    fun decrypt(ciphertext: String): String
}

/** AES-256-GCM with a key that never leaves the Android Keystore. Output is Base64(iv || ciphertext). */
class KeystoreTokenCipher(private val keyAlias: String) : TokenCipher {

    @Inject
    constructor() : this(DEFAULT_KEY_ALIAS)

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    override fun decrypt(ciphertext: String): String {
        val bytes = Base64.getDecoder().decode(ciphertext)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val DEFAULT_KEY_ALIAS = "forgeline_account_tokens"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
