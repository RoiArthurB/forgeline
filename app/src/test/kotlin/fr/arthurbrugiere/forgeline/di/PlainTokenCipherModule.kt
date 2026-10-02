package fr.arthurbrugiere.forgeline.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import fr.arthurbrugiere.forgeline.core.data.account.TokenCipher
import fr.arthurbrugiere.forgeline.core.data.di.TokenCipherModule

/**
 * There is no Android Keystore off a device, so app-level tests that sign in keep their made-up tokens unencrypted.
 * The real cipher is tested on the emulator (KeystoreTokenCipherTest).
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [TokenCipherModule::class])
object PlainTokenCipherModule {
    @Provides
    fun provideTokenCipher(): TokenCipher = object : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext

        override fun decrypt(ciphertext: String) = ciphertext
    }
}
