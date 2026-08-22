package dev.tramai.structured

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.core.structured.StructuredOutputContract
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.structured.descriptor.StructuredDescriptorCache
import dev.tramai.structured.descriptor.StructuredJsonShapeValidator
import dev.tramai.structured.descriptor.StructuredSchemaRenderer
import dev.tramai.structured.descriptor.StructuredTypeCompiler
import dev.tramai.structured.descriptor.StructuredValueValidator
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType

/**
 * Jackson-based structured output handler.
 *
 * Orchestration only: compiles the target type once into a language-neutral
 * [dev.tramai.structured.descriptor.StructuredTypeDescriptor] (cached per
 * instance), then delegates schema generation, raw JSON shape validation, and
 * runtime value validation to descriptor-driven consumers. No reflection or
 * Jackson introspection lives here — Kotlin and JavaBean differences are
 * resolved inside descriptor compilation and disappear afterwards.
 */
class JacksonStructuredOutputHandler(
    private val objectMapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .build(),
) : StructuredOutputHandler {

    private val typeCompiler = StructuredTypeCompiler(objectMapper)
    private val descriptors = StructuredDescriptorCache()
    private val schemaRenderer = StructuredSchemaRenderer()
    private val shapeValidator = StructuredJsonShapeValidator()
    private val valueValidator = StructuredValueValidator()

    override fun createContract(targetType: KType): StructuredOutputContract = StructuredOutputContract(
        targetType = targetType,
        schemaJson = objectMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(schemaRenderer.render(descriptorFor(targetType))),
    )

    override fun analyze(
        rawResponse: String,
        targetType: KType,
    ): StructuredOutputResult {
        val jsonCandidate = try {
            extractJsonCandidate(rawResponse)
        } catch (error: IllegalArgumentException) {
            return StructuredOutputResult.Failure(
                rawResponse = rawResponse,
                errorSummary = "Could not extract JSON content from the model response",
                feedbackMessage = "Your previous response did not contain valid JSON. Return only valid JSON that matches the requested schema.",
            )
                .also { it.failure = error }
        }

        val javaType = objectMapper.typeFactory.constructType(targetType.javaType)

        // Parse once for pre-deserialisation shape validation.
        // Required for primitive fields (int, double, boolean) that can never be
        // null after Jackson deserialisation and therefore cannot be detected post-hoc.
        val jsonNode = try {
            objectMapper.readTree(jsonCandidate)
        } catch (error: Exception) {
            return StructuredOutputResult.Failure(
                rawResponse = rawResponse,
                errorSummary = "Could not parse the JSON payload",
                feedbackMessage = "Your previous response contained JSON that could not be parsed into the requested output type. Return corrected JSON only.",
            )
                .also { it.failure = error }
        }

        val descriptor = descriptorFor(targetType)

        shapeValidator.validate(jsonNode, descriptor, "")?.let { error ->
            return StructuredOutputResult.Failure(
                rawResponse = rawResponse,
                errorSummary = "Structured output failed validation",
                feedbackMessage = "Your previous response failed validation: $error. Return corrected JSON only.",
            ).also { it.failure = IllegalArgumentException(error) }
        }

        val value = try {
            objectMapper.readerFor(javaType).readValue<Any>(jsonCandidate)
        } catch (error: Exception) {
            return StructuredOutputResult.Failure(
                rawResponse = rawResponse,
                errorSummary = "Could not deserialize the JSON payload",
                feedbackMessage = "Your previous response contained JSON that could not be parsed into the requested output type. Return corrected JSON only.",
            )
                .also { it.failure = error }
        }

        val validationError = valueValidator.validate(value, descriptor, "")
        if (validationError != null) {
            return StructuredOutputResult.Failure(
                rawResponse = rawResponse,
                errorSummary = "Structured output failed validation",
                feedbackMessage = "Your previous response failed validation: $validationError. Return corrected JSON only.",
            ).also { it.failure = IllegalArgumentException(validationError) }
        }

        return StructuredOutputResult.Success(
            value = value,
            rawResponse = rawResponse,
        )
    }

    override fun generateSchema(type: KType): String = objectMapper.writerWithDefaultPrettyPrinter()
        .writeValueAsString(schemaRenderer.render(descriptorFor(type)))

    override fun deserialize(
        input: Any,
        targetType: KType
    ): Any {
        val node = when (input) {
            is JsonNode -> input
            is String -> objectMapper.readTree(input)
            else -> objectMapper.valueToTree(input)
        }
        val javaType = objectMapper.typeFactory.constructType(targetType.javaType)
        return objectMapper.convertValue(node, javaType)
    }

    override fun serialize(value: Any): Any = objectMapper.valueToTree<JsonNode>(value)

    private fun descriptorFor(targetType: KType) =
        descriptors.getOrCompile(targetType) { typeCompiler.compile(it) }

    private fun extractJsonCandidate(rawResponse: String): String {
        val trimmed = rawResponse.trim()
        if (trimmed.startsWith("```")) {
            val lines = trimmed.lines()
            // Accept fenced code blocks because models often wrap JSON in markdown.
            if (lines.size >= 3 && lines.last().trim() == "```") {
                return lines.drop(1).dropLast(1).joinToString("\n").trim()
            }
        }

        val firstChar = trimmed.firstOrNull() ?: throw IllegalArgumentException("Empty response")
        val objectStart = trimmed.indexOf('{').takeIf { it >= 0 }
        val arrayStart = trimmed.indexOf('[').takeIf { it >= 0 }

        return when {
            // Detect whichever opening delimiter occurs first (handles prose-prefixed responses)
            arrayStart != null && (objectStart == null || arrayStart < objectStart) -> {
                val end = trimmed.lastIndexOf(']')
                require(end > arrayStart) {
                    "Could not find a matching closing bracket"
                }
                trimmed.substring(arrayStart, end + 1)
            }

            objectStart != null -> {
                val end = trimmed.lastIndexOf('}')
                require(end > objectStart) {
                    "Could not find a matching closing brace"
                }
                trimmed.substring(objectStart, end + 1)
            }

            else -> throw IllegalArgumentException(
                "Could not find a JSON object or array in the model response"
            )
        }
    }
}
