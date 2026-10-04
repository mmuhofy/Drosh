package dev.drosh.data.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Provides an [EncryptedSharedPreferences] instance for sensitive data
 * like the PIN lock — backed by AndroidX Security Crypto's master key.
 *
 * The master key is created on first access and stored in the Android
 * Keystore (AES-256, no user authentication required for app-wide PIN).
 *
 * Two separate stores, deliberately:
 *
 *  - [PinPref] holds the app-lock PIN. Losing it means re-prompting the user to
 *    set a PIN, so it must not be coupled to anything disposable.
 *  - [SecretPref] holds LLM API keys. A separate file and a separate master key
 *    alias mean a corrupt or cleared key store cannot take the PIN lock with it,
 *    and vice versa.
 *
 * Version note: the version catalog pins androidx-security-crypto 1.1.0. The
 * 1.1.0-alpha06 in the older note is stale.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @PinPref
    @Provides
    @Singleton
    fun provideEncryptedPrefs(
        @ApplicationContext context: Context,
    ): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "drosh_pin_prefs",
        MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Encrypted store for LLM API keys.
     *
     * Separate file and master key from [PinPref] — see the class note.
     */
    @SecretPref
    @Provides
    @Singleton
    fun provideSecretPrefs(
        @ApplicationContext context: Context,
    ): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "drosh_agent_secrets",
        MasterKey.Builder(context, AGENT_SECRETS_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class PinPref

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class SecretPref

    /** Distinct from the PIN store's alias so neither can decrypt the other. */
    private const val AGENT_SECRETS_KEY_ALIAS = "drosh_agent_secrets"
}
