package app.vela.core.data.ai

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Gemini → OpenRouter fallback chain, same shape as
 * [app.vela.core.data.traffic.TrafficOverlaySource]'s Google → HERE → TomTom chain: try the
 * primary provider; if it fails before producing any output (bad/missing key, quota exhausted,
 * network error, model retired), fall through to the next one instead of surfacing an error.
 *
 * Deliberately does NOT fall through once a provider has already started streaming real content —
 * restarting mid-answer with a different model would show the user a broken, duplicated response.
 * A failure after partial output just ends the stream there (the caller's existing catch already
 * surfaces a "Hiba történt: ..." message — see MapViewModel.askAiAboutPlace).
 */
class ChainedAiService(
    private val gemini: GeminiAiService,
    private val openRouter: OpenRouterAiService,
) : AiService {

    private val providers: List<Pair<String, AiService>>
        get() = listOf("Gemini" to gemini, "OpenRouter" to openRouter)

    /** Both settings-screen key fields call through here (see DataPrivacySettings.kt / MapViewModel's
     *  askAiAboutPlace + summarizePlaceWithAi) instead of reaching into the underlying providers
     *  directly, so a third provider can be added later without touching the UI/ViewModel wiring. */
    fun setGeminiApiKey(key: String) = gemini.setApiKey(key)
    fun setOpenRouterApiKey(key: String) = openRouter.setApiKey(key)

    override fun ask(
        question: String,
        currentLoc: LatLng?,
        currentAddress: String?,
        destination: String?,
        eta: String?,
        place: Place?,
        time: String?,
    ): Flow<String> = runChain { provider ->
        provider.ask(question, currentLoc, currentAddress, destination, eta, place, time)
    }

    override fun summarizePlace(place: Place): Flow<String> = runChain { provider ->
        provider.summarizePlace(place)
    }

    private fun runChain(call: (AiService) -> Flow<String>): Flow<String> = channelFlow {
        var lastError: Throwable? = null
        for ((name, provider) in providers) {
            var emittedAny = false
            try {
                call(provider).collect { chunk ->
                    emittedAny = true
                    send(chunk)
                }
                return@channelFlow // this provider finished successfully
            } catch (e: Throwable) {
                lastError = e
                android.util.Log.w("VelaAiChain", "$name failed" + (if (emittedAny) " (mid-stream, not falling through)" else " (no output yet, trying next)") + ": ${e.message}")
                if (emittedAny) {
                    // Already showed the user real content from this provider — don't restart with
                    // a different model mid-answer. Let the stream end here; the caller's own catch
                    // block handles surfacing this as an error if needed.
                    throw e
                }
                // else: fall through to the next provider in the loop
            }
        }
        throw AiServiceException(
            "Minden AI-szolgáltatás sikertelen (${providers.size} próbálkozás). Utolsó hiba: ${lastError?.message}",
            lastError,
        )
    }
}
