package dev.tramai.vectorstore

/**
 * A vector entry to be stored in a [VectorStore].
 *
 * @param id       Unique identifier for this entry.
 * @param vector   The embedding vector as a float array.
 * @param content  The original text content associated with the vector.
 * @param metadata Key-value metadata attached to this entry (default: empty map).
 */
data class VectorEntry(
    val id: String,
    val vector: FloatArray,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorEntry) return false
        return id == other.id &&
            vector.contentEquals(other.vector) &&
            content == other.content &&
            metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    override fun toString(): String {
        return "VectorEntry(id='$id', vector(size=${vector.size}), content='${content.take(50)}', metadata=$metadata)"
    }
}
