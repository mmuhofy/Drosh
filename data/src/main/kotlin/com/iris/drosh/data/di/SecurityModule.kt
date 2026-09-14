package com.iris.irisshell.data.di

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
 * Per AGENT.md §2 (TARGET STACK):
 *   - androidx-security-crypto: 1.1.0-alpha06
 *   - androidxBiometric: 1.2.0-alpha05 (reserved for future biometric unlock)
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
        "iris_pin_prefs",
        MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class PinPref
}
