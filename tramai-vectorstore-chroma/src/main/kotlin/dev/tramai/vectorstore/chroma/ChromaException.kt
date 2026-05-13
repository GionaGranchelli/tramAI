package dev.tramai.vectorstore.chroma

/**
 * Exception thrown when a Chroma API operation fails.
 */
class ChromaException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
