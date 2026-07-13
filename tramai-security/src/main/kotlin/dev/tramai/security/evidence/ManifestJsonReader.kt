package dev.tramai.security.evidence

/**
 * Minimal JSON object reader for extracting known fields from
 * the evidence bundle's manifest.json.
 *
 * This is NOT a general-purpose JSON parser. It handles only the
 * subset needed for manifest validation: a flat JSON object with
 * string values. It correctly handles:
 *
 * - Reordered properties
 * - Pretty-printed JSON (whitespace between tokens)
 * - bundleType as any property position
 * - Escaped quotes within string values
 *
 * It rejects:
 * - Nested objects (intentionally unsupported)
 * - Arrays as top-level values
 * - Malformed JSON (missing commas, trailing garbage)
 * - Duplicate keys
 *
 * Unlike a lenient parser, this reader validates the entire object
 * structure even after finding the target key. This prevents
 * malformed content after `bundleType` from going undetected.
 */
internal object ManifestJsonReader {

    /**
     * Parses a JSON object from [content] and returns the string value
     * for [key], or `null` if the key is not present.
     *
     * @throws IllegalArgumentException if content is not valid JSON
     *         or the key exists with a non-string value
     */
    fun readString(content: String, key: String): String? {
        val trimmed = content.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
            "Manifest must be a JSON object"
        }

        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return null

        return parseFieldValue(inner, key)
    }

    /**
     * Scans comma-separated key-value pairs inside a JSON object body
     * and returns the string value for [targetKey].
     *
     * Handles JSON string values that may contain escaped quotes.
     */
    private fun parseFieldValue(body: String, targetKey: String): String? {
        var pos = 0
        var targetValue: String? = null

        while (pos < body.length) {
            // Skip whitespace
            pos = skipWhitespace(body, pos)
            if (pos >= body.length) break

            // Expect a quoted key
            val keyStart = pos
            if (body[pos] != '"') {
                throw IllegalArgumentException(
                    "Expected quoted key at position $pos, got: ${body[pos]}"
                )
            }
            val keyEnd = findStringEnd(body, pos + 1)
            val key = body.substring(keyStart + 1, keyEnd)
            pos = keyEnd + 1

            // Skip whitespace and colon
            pos = skipWhitespace(body, pos)
            if (pos >= body.length || body[pos] != ':') {
                throw IllegalArgumentException(
                    "Expected ':' after key at position $pos"
                )
            }
            pos = skipWhitespace(body, pos + 1)
            if (pos >= body.length) {
                throw IllegalArgumentException(
                    "Unexpected end after colon at position $pos"
                )
            }

            // Read value
            if (body[pos] == '"') {
                val valueEnd = findStringEnd(body, pos + 1)
                val value = body.substring(pos + 1, valueEnd)
                if (key == targetKey) {
                    require(targetValue == null) {
                        "Duplicate key '$targetKey'"
                    }
                    targetValue = value
                }
                pos = valueEnd + 1
            } else {
                // Skip non-string values (numbers, booleans, null, objects, arrays)
                if (key == targetKey) {
                    throw IllegalArgumentException(
                        "Value for '$targetKey' must be a string"
                    )
                }
                pos = skipValue(body, pos)
            }

            // Skip whitespace and required comma
            pos = skipWhitespace(body, pos)
            if (pos >= body.length) break
            if (body[pos] == '}') break  // End of object
            if (body[pos] == ',') {
                pos++
                // Check for trailing comma immediately after consuming it
                val afterComma = skipWhitespace(body, pos)
                if (afterComma >= body.length) {
                    throw IllegalArgumentException(
                        "Trailing comma at end of object"
                    )
                }
                if (body[afterComma] == '}') {
                    throw IllegalArgumentException(
                        "Trailing comma before '}'"
                    )
                }
            } else {
                throw IllegalArgumentException(
                    "Expected ',' or '}' at position $pos, got: ${body[pos]}"
                )
            }
        }

        return targetValue
    }

    /**
     * Finds the end of a JSON string (closing quote), handling
     * escaped characters.
     */
    private fun findStringEnd(content: String, start: Int): Int {
        var pos = start
        while (pos < content.length) {
            when (content[pos]) {
                '\\' -> pos += 2 // Skip escaped character
                '"' -> return pos
                else -> pos++
            }
        }
        throw IllegalArgumentException("Unterminated string starting at position $start")
    }

    /**
     * Skips over a non-string JSON value (number, boolean, null, object, array).
     */
    private fun skipValue(content: String, start: Int): Int {
        var pos = start
        var depth = 0
        while (pos < content.length) {
            when (content[pos]) {
                '{', '[' -> depth++
                '}', ']' -> {
                    if (depth == 0) return pos
                    depth--
                    if (depth < 0) return pos + 1
                }
                '"' -> pos = findStringEnd(content, pos + 1)
                ',' -> if (depth == 0) return pos
            }
            pos++
        }
        return pos
    }

    private fun skipWhitespace(content: String, start: Int): Int {
        var pos = start
        while (pos < content.length && content[pos].isWhitespace()) {
            pos++
        }
        return pos
    }
}
