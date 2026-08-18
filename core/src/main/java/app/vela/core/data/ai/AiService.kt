package app.vela.core.data.ai

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlinx.coroutines.flow.Flow

/**
 * Interface for AI-powered assistant services.
 */
interface AiService {
    /**
     * Ask a question with full context (location, time, destination).
     */
    fun ask(
        question: String,
        currentLoc: LatLng? = null,
        currentAddress: String? = null,
        destination: String? = null,
        eta: String? = null,
        place: Place? = null,
        time: String? = null
    ): Flow<String>

    /**
     * Summarize a [Place] based on its details and reviews.
     */
    fun summarizePlace(place: Place): Flow<String>
}
