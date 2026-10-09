package dev.drosh.data.agent

import dev.drosh.domain.agent.CatalogProvider
import dev.drosh.domain.agent.CatalogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the fetched catalog and exposes it as state the settings screen renders.
 *
 * Inspired by: github.com/anomalyco/opencode — packages/core/src/models-dev.ts
 * Adapted for Drosh — dev.drosh
 *
 * ## Read the cache, then the network
 *
 * [load] serves whatever is on disk immediately and only touches the network when
 * there is nothing usable there. A user who has opened the app before gets a
 * populated list with no request at all; a user on their first launch waits once
 * and then never again. [refresh] skips the cache read and is what the retry
 * button calls.
 *
 * ## Why state and not a suspend function
 *
 * The settings screen has to render something while the fetch is in flight, and
 * again if it fails. A `StateFlow<CatalogState>` gives it all three cases without
 * the screen inventing its own loading flag and its own error path.
 */
@Singleton
class ProviderCatalogRepository @Inject constructor(
    private val source: ProviderCatalogSource,
) {

    private val _state = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    /** Providers currently loaded, or empty before the first [load] completes. */
    val providers: List<CatalogProvider>
        get() = (_state.value as? CatalogState.Ready)?.providers.orEmpty()

    /** Serve from disk when possible; fetch only when there is nothing there. */
    suspend fun load() {
        val cached = source.readCache()
        if (cached != null) {
            _state.value = CatalogState.Ready(cached)
            return
        }
        refresh()
    }

    /** Fetch from the network, falling back to the cache when that fails. */
    suspend fun refresh() {
        _state.value = CatalogState.Loading
        _state.value = try {
            CatalogState.Ready(source.fetch())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A stale catalog beats no catalog: the endpoint being unreachable is
            // not a reason to hide 225 providers that were listed yesterday.
            val cached = source.readCache()
            if (cached != null) {
                CatalogState.Ready(cached)
            } else {
                CatalogState.Failed(
                    message = failure.message ?: "Could not load the provider catalog",
                    retryable = true,
                )
            }
        }
    }

    /** Look up one model's catalog entry, for the effort list and output limit. */
    fun model(providerId: String, modelId: String) =
        provider(providerId)?.models?.get(modelId)

    /** Look up one provider, or null when it is not in the loaded catalog. */
    fun provider(providerId: String): CatalogProvider? =
        providers.firstOrNull { it.id == providerId }
}
