package io.aurora.structured

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.aurora.core.annotations.AiDescription
import io.aurora.core.annotations.AiMinItems
import io.aurora.core.annotations.AiRange
import io.aurora.core.structured.StructuredOutputContract
import io.aurora.core.structured.StructuredOutputHandler
import io.aurora.core.structured.StructuredOutputResult
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaType

/**
 * Jackson-based structured output handler with schema generation and annotation-driven validation.
 */
class JacksonStructuredOutputHandler(
    private val objectMapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .build(),
) : StructuredOutputHandler {

    override fun createContract(targetType: KType): StructuredOutputContract = StructuredOutputContract(
        targetType = targetType,
        schemaJson = objectMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(schemaForType(targetType)),
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
                errorSummary = error.message ?: "Could not extract JSON content from the model response",
                feedbackMessage = "Your previous response did not contain valid JSON. Return only valid JSON that matches the requested schema.",
            )
        }

        val value = try {
            val javaType = objectMapper.typeFactory.constructType(targetType.javaType)
            objectMapper.readerFor(javaType).readValue<Any>(jsonCandidate)
        } catch (error: Exception) {
            return StructuredOutputResult.Failure(
                rawResponse = rawResponse,
                errorSummary = error.message ?: "Could not deserialize the JSON payload",
                feedbackMessage = "Your previous response contained JSON that could not be parsed into the requested output type. Return corrected JSON only.",
            )
        }

        val validationError = validateValue(value, targetType)
        if (validationError != null) {
            return StructuredOutputResult.Failure(
                rawResponse = rawResponse,
                errorSummary = validationError,
                feedbackMessage = "Your previous response failed validation: $validationError. Return corrected JSON only.",
            )
        }

        return StructuredOutputResult.Success(
            value = value,
            rawResponse = rawResponse,
        )
    }

    private fun schemaForType(targetType: KType): Map<String, Any?> {
        val classifier = targetType.classifier
        return when (classifier) {
            String::class -> scalarSchema("string", targetType)
            Int::class, Long::class, Short::class -> scalarSchema("integer", targetType)
            Float::class, Double::class -> scalarSchema("number", targetType)
            Boolean::class -> scalarSchema("boolean", targetType)
            List::class, MutableList::class -> listSchema(targetType)
            Map::class, MutableMap::class -> error("Unsupported structured output type: $targetType")
            is KClass<*> -> objectSchema(classifier, targetType)
            else -> error("Unsupported structured output type: $targetType")
        }
    }

    private fun scalarSchema(
        type: String,
        targetType: KType,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "type" to type,
    ).also { schema ->
        if (targetType.isMarkedNullable) {
            schema["nullable"] = true
        }
    }

    private fun listSchema(targetType: KType): Map<String, Any?> {
        val itemType = targetType.arguments.firstOrNull()?.type
            ?: error("List structured output type must declare an item type: $targetType")
        return linkedMapOf<String, Any?>(
            "type" to "array",
            "items" to schemaForType(itemType),
        ).also { schema ->
            if (targetType.isMarkedNullable) {
                schema["nullable"] = true
            }
        }
    }

    private fun objectSchema(
        type: KClass<*>,
        targetType: KType,
    ): Map<String, Any?> {
        val visibleProperties = type.memberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .sortedBy { it.name }

        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()

        visibleProperties.forEach { property ->
            properties[property.name] = propertySchema(property)
            if (!property.returnType.isMarkedNullable) {
                required += property.name
            }
        }

        return linkedMapOf<String, Any?>(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to false,
        ).also { schema ->
            if (targetType.isMarkedNullable) {
                schema["nullable"] = true
            }
        }
    }

    private fun propertySchema(property: KProperty1<out Any, *>): Map<String, Any?> {
        val schema = schemaForType(property.returnType).toMutableMap()
        property.findAnnotation<AiDescription>()?.let { schema["description"] = it.value }
        property.findAnnotation<AiRange>()?.let {
            schema["minimum"] = it.min
            schema["maximum"] = it.max
        }
        property.findAnnotation<AiMinItems>()?.let {
            schema["minItems"] = it.value
        }
        return schema
    }

    private fun validateValue(
        value: Any?,
        targetType: KType,
    ): String? {
        if (value == null) {
            return if (targetType.isMarkedNullable) null else "Value must not be null"
        }

        val classifier = targetType.classifier
        return when (classifier) {
            String::class, Int::class, Long::class, Short::class, Float::class, Double::class, Boolean::class -> null
            List::class, MutableList::class -> {
                val itemType = targetType.arguments.firstOrNull()?.type ?: return null
                (value as? List<*>)?.forEachIndexed { index, item ->
                    validateValue(item, itemType)?.let { return "Item $index: $it" }
                }
                null
            }
            is KClass<*> -> validateObject(value, classifier)
            else -> null
        }
    }

    private fun validateObject(
        value: Any,
        type: KClass<*>,
    ): String? {
        type.memberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .sortedBy { it.name }
            .forEach { property ->
                @Suppress("UNCHECKED_CAST")
                property as KProperty1<Any, *>
                property.isAccessible = true

                val propertyValue = property.get(value)
                if (propertyValue == null && !property.returnType.isMarkedNullable) {
                    return "Property '${property.name}' must not be null"
                }

                property.findAnnotation<AiRange>()?.let { range ->
                    val numericValue = propertyValue as? Number
                        ?: return "Property '${property.name}' must be numeric for @AiRange"
                    val asDouble = numericValue.toDouble()
                    if (asDouble < range.min || asDouble > range.max) {
                        return "Property '${property.name}' must be between ${range.min} and ${range.max}"
                    }
                }

                property.findAnnotation<AiMinItems>()?.let { minItems ->
                    val collectionValue = propertyValue as? Collection<*>
                        ?: return "Property '${property.name}' must be a collection for @AiMinItems"
                    if (collectionValue.size < minItems.value) {
                        return "Property '${property.name}' must contain at least ${minItems.value} items"
                    }
                }

                validateValue(propertyValue, property.returnType)?.let {
                    return "Property '${property.name}': $it"
                }
            }

        return null
    }

    private fun extractJsonCandidate(rawResponse: String): String {
        val trimmed = rawResponse.trim()
        if (trimmed.startsWith("```")) {
            val lines = trimmed.lines()
            // Accept fenced code blocks because models often wrap JSON in markdown.
            if (lines.size >= 3 && lines.last().trim() == "```") {
                return lines.drop(1).dropLast(1).joinToString("\n").trim()
            }
        }

        val firstObject = trimmed.indexOf('{')
        val lastObject = trimmed.lastIndexOf('}')
        if (firstObject >= 0 && lastObject > firstObject) {
            return trimmed.substring(firstObject, lastObject + 1)
        }

        val firstArray = trimmed.indexOf('[')
        val lastArray = trimmed.lastIndexOf(']')
        if (firstArray >= 0 && lastArray > firstArray) {
            return trimmed.substring(firstArray, lastArray + 1)
        }

        throw IllegalArgumentException("Could not find a JSON object or array in the model response")
    }
}
