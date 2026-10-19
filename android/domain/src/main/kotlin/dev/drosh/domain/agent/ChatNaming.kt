package dev.drosh.domain.agent

/**
 * The one extra call a first message earns, decided before anything is sent.
 *
 * ## Why this decision lives here and not in the ViewModel
 *
 * It is the *policy* half of "name the session while it answers", and the policy
 * is what a test can hold still: a later message earns nothing, a short message
 * earns nothing, and a side-job model that resolves to nothing falls back to the
 * conversation's own. The ViewModel owns the other half — launching the two
 * calls so neither waits for the other — which is a coroutine shape and belongs
 * where the scope is.
 *
 * ## Only the first message
 *
 * [firstMessage] is the whole guard. A naming request made for every prompt
 * would charge the user for a title after every reply, and would overwrite a
 * name they had already read. The caller answers it from the transcript: has
 * anything been answered yet.
 *
 * ## The side job is what pays for the title
 *
 * The provider and model come from [SideJobModel.resolve], which puts the call
 * on the cheap model when one is configured and on the chat's own model when
 * nothing is. A title on the frontier model is the case this whole setting
 * exists to prevent.
 */
object ChatNaming {

    /**
     * What to send to the naming suggester, or null for "nothing to ask".
     *
     * Null is a normal answer, not a failure: it is what a second message, a
     * two-word question, or a chat the user has already renamed look like. The
     * caller treats it as "no extra call" and carries on with the run alone.
     *
     * @param firstMessage whether nothing has been answered in this chat yet
     * @param prompt the user's first message, verbatim — never a paraphrase
     * @param sideJobModel the stored `[agent] side_job_model`, blank for the
     *        chat's own model
     * @param catalog the provider catalog as last fetched
     * @param mainProviderId the provider the conversation runs on
     * @param mainModelId the model the conversation runs on
     */
    fun request(
        firstMessage: Boolean,
        prompt: String,
        sideJobModel: String,
        catalog: CatalogState,
        mainProviderId: String,
        mainModelId: String,
    ): ChatTitleRequest? {
        if (!firstMessage) return null
        if (!ChatTitle.isWorthNaming(prompt)) return null
        val side = SideJobModel.resolve(
            catalog = catalog,
            sideJobModel = sideJobModel,
            mainProviderId = mainProviderId,
            mainModelId = mainModelId,
        )
        return ChatTitleRequest(
            providerId = side.providerId,
            modelId = side.modelId,
            prompt = prompt,
        )
    }
}
