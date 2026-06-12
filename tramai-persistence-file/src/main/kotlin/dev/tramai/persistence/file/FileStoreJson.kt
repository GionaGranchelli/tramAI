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
 * - [JsonInclude.Include.NON_ABSENT] to omit null optional fields
 * - **FAIL_ON_TRAILING_TOKENS** — reject JSON with unexpected trailing content
 * - No polymorphic typing (security requirement — never deserialise arbitrary types)
 */
internal val FILE_STORE_JSON: ObjectMapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .serializationInclusion(JsonInclude.Include.NON_ABSENT)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
    .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
    .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, false)
    .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
    .build()

/** Maximum accepted JSON payload size (10 MB). */
private const val MAX_JSON_SIZE = 10_485_760

/**
 * Deserialises [json] into [T] with strict size limits and safe error reporting.
 *
 * @throws IllegalArgumentException with a fixed safe reason code on any failure.
 */
internal inline fun <reified T : Any> strictReadValue(json: String): T {
    require(json.length <= MAX_JSON_SIZE) { "json-payload-too-large" }
    val trimmed = json.trim()
    return try {
        FILE_STORE_JSON.readValue(trimmed)
    } catch (e: Exception) {
        throw IllegalArgumentException("json-deserialisation-failed", e)
    }
}
