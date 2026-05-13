package dev.tramai.embedding

/**
 * Exception thrown when an embedding operation fails.
 */
class EmbeddingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
