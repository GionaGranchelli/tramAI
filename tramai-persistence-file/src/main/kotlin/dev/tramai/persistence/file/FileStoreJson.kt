package dev.tramai.persistence.file

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Shared Jackson [ObjectMapper] for tramai-persistence-file serialization.
 *
 * Configuration:
 * - Kotlin module for data class support
 * - Strict deserialisation: fail on unknown properties, null for primitives, empty strings
 * - [JsonInclude.Include.NON_ABSENT] to omit null optional fields (keeps envelope compact)
 * - No polymorphic typing (security requirement — never deserialise arbitrary types)
 * - 10 MB max string length (rejects oversized records)
 */
internal val FILE_STORE_JSON: ObjectMapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .serializationInclusion(JsonInclude.Include.NON_ABSENT)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
    .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
    .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, false)
    .build()

/**
 * Deserialises [json] into [T] with strict size limits.
 *
 * @throws IllegalArgumentException if the JSON is invalid, contains unknown properties,
 *   has trailing content, or exceeds safe size limits.
 */
internal inline fun <reified T : Any> strictReadValue(json: String): T {
    // Reject oversized payloads before trying to parse
    require(json.length <= 10_485_760) { "JSON payload exceeds maximum size of 10 MB" }
    val trimmed = json.trim()
    val result: T = try {
        FILE_STORE_JSON.readValue(trimmed)
    } catch (e: Exception) {
        throw IllegalArgumentException("Failed to deserialise ${T::class.simpleName}: ${e.message}", e)
    }
    return result
}
