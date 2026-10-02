package fr.arthurbrugiere.forgeline.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.core.data.account.KeystoreTokenCipher
import fr.arthurbrugiere.forgeline.core.data.account.TokenCipher

/** On its own so tests off a device, where there is no Android Keystore, can sign in with another cipher. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TokenCipherModule {
    @Binds
    abstract fun bindTokenCipher(impl: KeystoreTokenCipher): TokenCipher
}
