package app.vela.core.data.ai

import app.vela.core.model.Place
import kotlinx.coroutines.flow.Flow

/**
 * Interface for AI-powered assistant services.
 */
interface AiService {
    /**
     * Ask a question about a specific [Place].
     * Returns a [Flow] of the response string (streaming).
     */
    fun askAboutPlace(place: Place, question: String): Flow<String>

    /**
     * Summarize a [Place] based on its details and reviews.
     */
    fun summarizePlace(place: Place): Flow<String>
}
