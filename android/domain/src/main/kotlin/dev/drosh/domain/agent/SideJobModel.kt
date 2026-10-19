package dev.drosh.domain.agent

/**
 * The model that answers the jobs the user did not ask for.
 *
 * Naming a conversation, writing a preview line, deciding how much of a tool's
 * output is worth keeping: these are calls made *beside* the answer, and they are
 * the reason a session on an expensive model still feels cheap to run. They are
 * also the reason it does not, when the setting that should have pointed them at
 * a small model was never there.
 *
 * ## Why a model id string, not a second provider and model
 *
 * The setting is one string because that is what a user can type into a
 * hand-edited `settings.toml`, and because two fields have to be kept in step by
 * hand. It is resolved against the catalog rather than against the selected
 * provider: a side job is worth handing to whichever provider has the cheap
 * model, and "the one you happen to be running" is rarely it.
 *
 * ## Every unknown value falls back to the conversation's own model
 *
 * [resolve] returns the main model when the setting is blank, when the id is in
 * no provider's catalog, or when the provider that has it has no key. A side job
 * that cannot run is not worth an error: the name a session gets is a nicety,
 * and the conversation has to answer either way.
 */
data class SideJobModel(
    val providerId: String,
    val modelId: String,
) {
    companion object {

        /**
         * Resolve which provider and model answer the side job.
         *
         * @param catalog the provider catalog as last fetched, so a model id the
         *        user typed by hand resolves the same way one picked from a list
         *        does. [CatalogState] other than `Ready` resolves to the main
         *        model, which is the honest answer: with no catalog there is
         *        nothing to resolve *to*.
         * @param sideJobModel the stored setting, blank for "use the main model"
         * @param mainProviderId the provider the conversation runs on
         * @param mainModelId the model the conversation runs on
         */
        fun resolve(
            catalog: CatalogState,
            sideJobModel: String,
            mainProviderId: String,
            mainModelId: String,
        ): SideJobModel {
            val requested = sideJobModel.trim()
            // Blank is the common case and the one that must cost nothing: a user
            // who never opened the setting runs side jobs on the main model.
            if (requested.isEmpty()) return main(mainProviderId, mainModelId)

            val providers = (catalog as? CatalogState.Ready)?.providers ?: emptyList()
            // The provider that declares it, rather than the selected one: the
            // whole point is to reach a cheap model that is somewhere else.
            val owner = providers.firstOrNull { it.models.containsKey(requested) }
                ?: return main(mainProviderId, mainModelId)

            return SideJobModel(providerId = owner.id, modelId = requested)
        }

        /** The conversation's own provider and model, which is the fallback. */
        fun main(providerId: String, modelId: String): SideJobModel =
            SideJobModel(providerId = providerId, modelId = modelId)
    }
}

/**
 * Whether [modelId] is one the catalog has heard of, from any provider.
 *
 * Used by the settings row to say "that model is not available any more" rather
 * than silently falling back to the main model, which reads as the setting being
 * ignored.
 */
fun CatalogState.hasModel(modelId: String): Boolean {
    if (modelId.isBlank()) return false
    val providers = (this as? CatalogState.Ready)?.providers ?: return false
    return providers.any { it.models.containsKey(modelId) }
}
