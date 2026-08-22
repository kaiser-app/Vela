package app.vela.core.data.ai

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback AI provider behind [ChainedAiService] — OpenRouter's OpenAI-compatible chat completions
 * endpoint, targeting a free-tier model. Same role in the AI chain that HERE/TomTom play for
 * [app.vela.core.data.traffic.TrafficOverlaySource]: Gemini is primary, this is what catches the
 * request when Gemini's own quota/availability runs out (see the 2026-08-21 "AI integráció eléggé
 * korlátoz" report — Gemini's free tier is 5 requests/day for some models).
 *
 * MODEL NAME NEEDS LIVE VERIFICATION: OpenRouter's free-model catalog changes over time (models
 * get added/retired from the ":free" tier fairly often). [MODEL] below is a reasonable default as
 * of this writing but WILL need spot-checking against https://openrouter.ai/models?max_price=0
 * before shipping — this sandbox can't reach openrouter.ai to verify live. If the configured model
 * has been retired, every call fails the same way a bad Gemini key does (caught by the chain,
 * falls through to "both providers failed" — see ChainedAiService), so a stale model name degrades
 * gracefully rather than crashing, but it's still worth checking.
 */
@Singleton
class OpenRouterAiService @Inject constructor() : AiService {

    private var apiKey: String = ""

    fun setApiKey(key: String) {
        apiKey = key
    }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(45, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        // TODO verify against https://openrouter.ai/models?max_price=0 before shipping — see the
        // class doc comment. A few historically-free alternatives to try if this one 404s/retires:
        // "google/gemma-2-9b-it:free", "mistralai/mistral-7b-instruct:free",
        // "meta-llama/llama-3.2-3b-instruct:free".
        private const val MODEL = "meta-llama/llama-3.1-8b-instruct:free"
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    }

    override fun ask(
        question: String,
        currentLoc: LatLng?,
        currentAddress: String?,
        destination: String?,
        eta: String?,
        place: Place?,
        time: String?,
    ): Flow<String> = streamChat(
        system = "Te egy segítőkész navigációs asszisztens vagy a NA-VIGATOR térkép alkalmazásban. " +
            "A válaszaid legyenek lényegretörőek, barátságosak és magyar nyelvűek.",
        user = "Környezeti információk:\n" + buildAiContext(currentLoc, currentAddress, destination, eta, place, time) +
            "\n\nKérdés: $question",
    )

    override fun summarizePlace(place: Place): Flow<String> = streamChat(
        system = "Te egy helyszín-összefoglaló asszisztens vagy. Készíts egy rövid, 2-3 mondatos " +
            "összefoglalót a helyről a megadott adatok és vélemények alapján magyarul.",
        user = "Foglald össze ezt a helyet: ${place.name}, ${place.category}, ${place.address}. " +
            "Értékelés: ${place.rating}. Vélemény: ${place.featuredReview ?: "nincs"}",
    )

    private fun streamChat(system: String, user: String): Flow<String> = flow {
        if (apiKey.isBlank()) {
            throw AiServiceException("OpenRouter API kulcs nincs beállítva")
        }

        val bodyJson = buildString {
            append("{")
            append("\"model\":${jsonString(MODEL)},")
            append("\"stream\":true,")
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":${jsonString(system)}},")
            append("{\"role\":\"user\",\"content\":${jsonString(user)}}")
            append("]}")
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            // OpenRouter asks for these on a best-effort basis for their public leaderboard; not
            // required for the API to function.
            .header("X-Title", "NA-VIGATOR")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        val response = try {
            call.execute()
        } catch (e: Exception) {
            throw AiServiceException("OpenRouter hálózati hiba: ${e.message}", e)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching { resp.body?.string() }.getOrNull().orEmpty().take(300)
                throw AiServiceException("OpenRouter HTTP ${resp.code}: $errBody")
            }
            val source = resp.body?.source() ?: throw AiServiceException("OpenRouter: üres válasz")

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue

                val chunk = extractDeltaContent(payload)
                if (!chunk.isNullOrEmpty()) emit(chunk)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Pulls choices[0].delta.content out of one OpenRouter/OpenAI-format SSE data line. Written
     *  defensively (returns null on any shape surprise) rather than with a full typed model,
     *  since streaming delta chunks omit fields inconsistently between providers/models. */
    private fun extractDeltaContent(payloadJson: String): String? = runCatching {
        val root = json.parseToJsonElement(payloadJson).jsonObject
        val choices = root["choices"] as? kotlinx.serialization.json.JsonArray ?: return@runCatching null
        val delta = choices.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return@runCatching null
        delta["content"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun jsonString(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

    private fun buildAiContext(
        loc: LatLng?,
        addr: String?,
        dest: String?,
        eta: String?,
        place: Place?,
        time: String?,
    ): String = buildString {
        appendLine("Aktuális idő: ${time ?: "Ismeretlen"}")
        if (loc != null) appendLine("Helyzet (koordináta): ${loc.lat}, ${loc.lng}")
        if (addr != null) appendLine("Helyzet (cím): $addr")
        if (dest != null) appendLine("Úti cél: $dest")
        if (eta != null) appendLine("Várható érkezés: $eta")
        if (place != null) {
            appendLine("\nKiválasztott helyszín adatai:")
            appendLine("Név: ${place.name}")
            appendLine("Kategória: ${place.category ?: "Ismeretlen"}")
            appendLine("Cím: ${place.address ?: "Ismeretlen"}")
        }
    }
}
