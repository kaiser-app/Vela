package app.vela.core.data.ai

/** Thrown by [AiService] implementations on failure, instead of emitting an error string into the
 *  Flow<String> — lets [ChainedAiService] tell "this provider failed, try the next one" apart from
 *  "this provider succeeded and the text just happens to start with 'Hiba'". */
class AiServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)
