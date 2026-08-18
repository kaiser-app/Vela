package app.vela.core.data.ai

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
            modelName = "gemini-1.5-flash", // No "models/" prefix, SDK handles it
            apiKey = apiKey,
            systemInstruction = content { text(systemInstruction) }
        )
    }

    override fun askAboutPlace(place: Place, question: String): Flow<String> = flow {
        if (apiKey.isBlank()) {
            emit("Hiba: Gemini API kulcs nincs beállítva a Beállításokban.")
            return@flow
        }

        try {
            val context = buildPlaceContext(place)
            val model = getModel("Te egy segítőkész utazási asszisztens vagy a Vela navigációs alkalmazásban. " +
                    "A válaszaid legyenek lényegretörőek, barátságosak és magyar nyelvűek.")
            
            val response = model.generateContentStream(content {
                text("Itt vannak az információk a helyről:\n$context\n\nKérdés: $question")
            })

            response.map { it.text ?: "" }.collect { emit(it) }
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: ""
            val friendlyError = when {
                msg.contains("API_KEY_INVALID") -> "Érvénytelen API kulcs. Ellenőrizd a Beállításokban!"
                msg.contains("404") -> "A Gemini modell nem található (404). Próbáld később."
                msg.contains("SAFETY") -> "Az AI biztonsági okokból elutasította a választ."
                else -> "AI Hiba: $msg"
            }
            emit(friendlyError)
        }
    }

    override fun summarizePlace(place: Place): Flow<String> = flow {
        if (apiKey.isBlank()) {
            emit("Hiba: Gemini API kulcs nincs beállítva.")
            return@flow
        }

        try {
            val context = buildPlaceContext(place)
            val model = getModel("Te egy helyszín-összefoglaló asszisztens vagy. " +
                    "Készíts egy rövid, 2-3 mondatos összefoglalót a helyről a megadott adatok és vélemények alapján magyarul.")

            val response = model.generateContentStream(content {
                text("Foglald össze ezt a helyet:\n$context")
            })

            response.map { it.text ?: "" }.collect { emit(it) }
        } catch (e: Exception) {
            emit("AI Hiba: ${e.localizedMessage}")
        }
    }

    private fun buildPlaceContext(place: Place): String {
        return buildString {
            appendLine("Név: ${place.name}")
            appendLine("Kategória: ${place.category ?: "Ismeretlen"}")
            appendLine("Cím: ${place.address ?: "Ismeretlen"}")
            appendLine("Értékelés: ${place.rating ?: "Nincs"} (${place.reviewCount ?: 0} vélemény)")
            if (place.editorialSummary != null) appendLine("Összefoglaló: ${place.editorialSummary}")
            if (place.hours.isNotEmpty()) appendLine("Nyitvatartás: ${place.hours.joinToString(", ")}")
            if (place.about.isNotEmpty()) {
                appendLine("Jellemzők:")
                place.about.forEach { section ->
                    appendLine("- ${section.title}: ${section.items.joinToString(", ")}")
                }
            }
            if (place.featuredReview != null) appendLine("Kiemelt vélemény: ${place.featuredReview}")
        }
    }
}
