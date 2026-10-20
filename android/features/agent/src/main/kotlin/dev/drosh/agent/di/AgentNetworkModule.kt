package dev.drosh.agent.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The HTTP client used for LLM traffic.
 *
 * ## Why this is not the app-wide client
 *
 * `data/di/NetworkModule.kt` provides a client tuned for decorative background
 * lookups: 5s connect, 5s read, 10s call. Reusing it for a token stream would
 * sever the connection every time the model pauses to think — and a 40-second
 * reasoning pause is completely normal. So the agent gets its own client.
 *
 * ## Timeouts
 *
 * `readTimeout = 0` and `callTimeout = 0` disable the respective deadlines.
 * That is the correct setting for SSE rather than a large number: between
 * tokens a stream may idle for minutes, and any finite read timeout eventually
 * fires on a legitimate wait. `connectTimeout` stays finite, because failing to
 * establish a connection never resolves itself.
 *
 * ## retryOnConnectionFailure = false
 *
 * OkHttp's automatic retry can resend a request whose body was already
 * transmitted. For an idempotent GET that is a non-event; for a POST that bills
 * tokens, it can pay for the same completion twice. Retries are the agent
 * loop's job, where they are counted, bounded and reported to the user.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentNetworkModule {

    @AgentHttpClient
    @Provides
    @Singleton
    fun provideAgentHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class AgentHttpClient
}
