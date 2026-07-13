package dev.tramai.security.evidence

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Reads a string value from a JSON manifest using Jackson.
 *
 * Validates the full JSON structure — missing commas, trailing commas,
 * invalid values, duplicate keys, nested objects, arrays, booleans,
 * numbers, and trailing content are all rejected by the Jackson parser.
 */
internal object ManifestJsonReader {

    private val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
        .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true)

    /**
     * Parses [content] as a JSON object and returns the string value
     * for [key], or `null` if the key is not present.
     *
     * @throws IllegalArgumentException if content is malformed JSON,
     *         is not a JSON object, or the value for [key] is not a string.
     */
    fun readString(content: String, key: String): String? {
        val trimmed = content.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
            "Manifest must be a JSON object"
        }

        return try {
            val tree = mapper.readTree(trimmed)
            val node = tree.get(key)
            when {
                node == null -> null
                node.isTextual -> node.asText()
                else -> throw IllegalArgumentException(
                    "Value for '$key' must be a string, got: ${node.nodeType}"
                )
            }
        } catch (e: JsonProcessingException) {
            throw IllegalArgumentException(
                "Failed to parse manifest.json: ${e.message}", e
            )
        }
    }
}
