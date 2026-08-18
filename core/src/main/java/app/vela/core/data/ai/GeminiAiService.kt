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

    // NOTE: This should be injected or read from a secure source.
    // For now, we'll assume it's provided via a property.
    private var apiKey: String = ""

    fun setApiKey(key: String) {
        apiKey = key
    }

    private fun getModel(systemInstruction: String): GenerativeModel {
        return GenerativeModel(
            modelName = "models/gemini-1.5-flash", // explicit model path
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
            emit("AI Hiba: ${e.localizedMessage ?: "Ismeretlen hiba történt a Gemini hívása közben."}")
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
