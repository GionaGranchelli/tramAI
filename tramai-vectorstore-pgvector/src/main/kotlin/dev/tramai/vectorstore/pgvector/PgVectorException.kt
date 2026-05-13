package dev.tramai.vectorstore.pgvector

/**
 * Exception thrown when a pgvector PostgreSQL operation fails.
 */
class PgVectorException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
