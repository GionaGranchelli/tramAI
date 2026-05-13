package dev.tramai.embedding

/**
 * Interface for models that convert text to vector embeddings.
 */
interface EmbeddingModel {
    /**
     * Returns a symbolic identifier for this embedding model provider (e.g., "openai", "ollama").
     */
    fun providerId(): String

    /**
     * Embeds a single text string into a float vector.
     *
     * @throws IllegalArgumentException if [text] is blank.
     */
    suspend fun embed(text: String): FloatArray

    /**
     * Embeds a batch of text strings into float vectors.
     *
     * @throws IllegalArgumentException if [texts] is empty.
     */
    suspend fun embedAll(texts: List<String>): List<FloatArray>

    /**
     * Returns the dimensionality of the embedding vectors produced by this model.
     */
    fun dimensions(): Int
}
