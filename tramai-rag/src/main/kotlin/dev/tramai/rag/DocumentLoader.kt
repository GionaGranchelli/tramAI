package dev.tramai.rag

/**
 * Loads a [Document] from a given [source] identifier.
 *
 * Implementations are responsible for fetching text content from their
 * respective source types (e.g., files, URLs, databases).
 */
interface DocumentLoader {
    /**
     * Loads text content from the specified [source] and returns a [Document].
     *
     * @throws IllegalArgumentException if [source] is blank or unsupported.
     * @throws Exception if the source cannot be read.
     */
    suspend fun load(source: String): Document
}
