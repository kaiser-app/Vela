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
            emit("Hiba: Gemini API kulcs nincs beállítva a Beállításokban.")
            return@flow
        }

        val context = buildAiContext(currentLoc, currentAddress, destination, eta, place, time)
        val model = getModel("Te egy segítőkész navigációs asszisztens vagy a Vela térkép alkalmazásban. " +
                "A válaszaid legyenek lényegretörőek, barátságosak és magyar nyelvűek. " +
                "Ismered a felhasználó helyzetét, úti célját és az aktuális időt.")

        var attempts = 0
        val maxAttempts = 5
        var lastException: Exception? = null

        while (attempts < maxAttempts) {
            try {
                val response = model.generateContentStream(content {
                    text("Környezeti információk:\n$context\n\nKérdés: $question")
                })

                response.map { it.text ?: "" }.collect { emit(it) }
                return@flow // Success!
            } catch (e: Exception) {
                attempts++
                lastException = e
                val msg = e.localizedMessage ?: ""
                
                // Only retry on 503 or potentially transient network errors
                if (attempts < maxAttempts && (msg.contains("503") || msg.contains("Unavailable") || msg.contains("Deadline"))) {
                    kotlinx.coroutines.delay(1000L * attempts) // Wait 1s, 2s, 3s...
                    continue
                } else {
                    break // Non-retryable error or ran out of attempts
                }
            }
        }

        val finalMsg = lastException?.localizedMessage ?: "Ismeretlen hiba"
        emit("AI Hiba (5 próbálkozás után): $finalMsg")
    }

    override fun summarizePlace(place: Place): Flow<String> = flow {
        if (apiKey.isBlank()) {
            emit("Hiba: Gemini API kulcs nincs beállítva.")
            return@flow
        }

        val model = getModel("Te egy helyszín-összefoglaló asszisztens vagy. " +
                "Készíts egy rövid, 2-3 mondatos összefoglalót a helyről a megadott adatok és vélemények alapján magyarul.")

        var attempts = 0
        val maxAttempts = 5
        var lastException: Exception? = null

        while (attempts < maxAttempts) {
            try {
                val response = model.generateContentStream(content {
                    text("Foglald össze ezt a helyet: ${place.name}, ${place.category}, ${place.address}. " +
                            "Értékelés: ${place.rating}. Vélemény: ${place.featuredReview ?: "nincs"}")
                })

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

        emit("AI Hiba: ${lastException?.localizedMessage}")
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
