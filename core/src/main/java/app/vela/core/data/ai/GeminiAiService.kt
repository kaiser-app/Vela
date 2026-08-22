package app.vela.core.data.ai

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiAiService @Inject constructor() : AiService {

    private var apiKey: String = ""

    fun setApiKey(key: String) {
        apiKey = key
    }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    private fun getModel(systemInstruction: String): GenerativeModel {
        return GenerativeModel(
            modelName = "gemini-flash-latest",
            apiKey = apiKey,
            systemInstruction = content { text(systemInstruction) }
        )
    }

    override fun ask(
        question: String,
        currentLoc: LatLng?,
        currentAddress: String?,
        destination: String?,
        eta: String?,
        place: Place?,
        time: String?
    ): Flow<String> = flow {
        if (apiKey.isBlank()) {
            throw AiServiceException("Gemini API kulcs nincs beállítva")
        }

        val context = buildAiContext(currentLoc, currentAddress, destination, eta, place, time)
        val model = getModel(
            "Te egy segítőkész navigációs asszisztens vagy a NA-VIGATOR térkép alkalmazásban. " +
                "A válaszaid legyenek lényegretörőek, barátságosak és magyar nyelvűek. " +
                "Ismered a felhasználó helyzetét, úti célját és az aktuális időt."
        )

        var attempts = 0
        val maxAttempts = 3 // was 5 — see ChainedAiService: a fast-failing Gemini gets to
        // OpenRouter sooner. 3 attempts (1s/2s/3s backoff) still absorbs a transient 503 without
        // making the user wait ~15s (5 attempts' worth) before the fallback even starts.
        var lastException: Exception? = null

        while (attempts < maxAttempts) {
            try {
                val response = model.generateContentStream(
                    content { text("Környezeti információk:\n$context\n\nKérdés: $question") }
                )
                response.map { it.text ?: "" }.collect { emit(it) }
                return@flow // success
            } catch (e: Exception) {
                attempts++
                lastException = e
                val msg = e.localizedMessage ?: ""
                if (attempts < maxAttempts && (msg.contains("503") || msg.contains("Unavailable") || msg.contains("Deadline"))) {
                    kotlinx.coroutines.delay(1000L * attempts)
                    continue
                } else {
                    break
                }
            }
        }

        throw AiServiceException("Gemini hiba ($attempts próbálkozás után): ${lastException?.localizedMessage}", lastException)
    }

    override fun summarizePlace(place: Place): Flow<String> = flow {
        if (apiKey.isBlank()) {
            throw AiServiceException("Gemini API kulcs nincs beállítva")
        }

        val model = getModel(
            "Te egy helyszín-összefoglaló asszisztens vagy. " +
                "Készíts egy rövid, 2-3 mondatos összefoglalót a helyről a megadott adatok és vélemények alapján magyarul."
        )

        var attempts = 0
        val maxAttempts = 3
        var lastException: Exception? = null

        while (attempts < maxAttempts) {
            try {
                val response = model.generateContentStream(
                    content {
                        text(
                            "Foglald össze ezt a helyet: ${place.name}, ${place.category}, ${place.address}. " +
                                "Értékelés: ${place.rating}. Vélemény: ${place.featuredReview ?: "nincs"}"
                        )
                    }
                )
                response.map { it.text ?: "" }.collect { emit(it) }
                return@flow
            } catch (e: Exception) {
                attempts++
                lastException = e
                val msg = e.localizedMessage ?: ""
                if (attempts < maxAttempts && (msg.contains("503") || msg.contains("Unavailable"))) {
                    kotlinx.coroutines.delay(1000L * attempts)
                    continue
                } else break
            }
        }

        throw AiServiceException("Gemini hiba: ${lastException?.localizedMessage}", lastException)
    }

    private fun buildAiContext(
        loc: LatLng?,
        addr: String?,
        dest: String?,
        eta: String?,
        place: Place?,
        time: String?
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
