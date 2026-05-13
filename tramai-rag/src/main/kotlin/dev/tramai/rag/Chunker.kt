package dev.tramai.rag

/**
 * Splits a [Document] into a list of smaller [Document] chunks.
 *
 * Implementations control how text is divided — by characters, tokens,
 * sentence boundaries, semantic segments, etc.
 */
interface Chunker {
    /**
     * Chunks the given [document] into a list of sub-documents.
     *
     * The resulting documents retain the original [Document.source] value and any
     * applicable metadata from the parent document.
     */
    fun chunk(document: Document): List<Document>
}
