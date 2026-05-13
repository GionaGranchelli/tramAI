package dev.tramai.rag

/**
 * A document loaded from a source for RAG processing.
 *
 * @param source   The origin of the document (e.g., file path, URL, database key).
 * @param content  The raw text content of the document.
 * @param metadata Key-value metadata attached to the document (default: empty map).
 */
data class Document(
    val source: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
)
