package dev.tramai.structured

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import dev.tramai.core.structured.StructuredOutputContract
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputResult
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
 *
 * Schema generation supports:
 * - **Kotlin classes** via `KClass.memberProperties` (reflection)
 * - **JavaBean classes** via Jackson deserialization introspection
 *
 * Validation mirrors the same language-specific path so that anything
 * declared in the schema is enforced at runtime.
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

        val javaType = objectMapper.typeFactory.constructType(targetType.javaType)

        // Pre-deserialization shape validation for JavaBean types.
        // Required for primitive properties that cannot be null after deserialization.
        val classifier = targetType.classifier
        if (classifier is KClass<*> && !classifier.isKotlinClass()) {
            val jsonNode = try {
                objectMapper.readTree(jsonCandidate)
            } catch (_: Exception) {
                return StructuredOutputResult.Failure(
                    rawResponse = rawResponse,
                    errorSummary = "Could not parse the JSON payload",
                    feedbackMessage = "Your previous response contained JSON that could not be parsed into the requested output type. Return corrected JSON only.",
                )
            }
            validateJavaBeanJsonShape(jsonNode, javaType, "")?.let { error ->
                return StructuredOutputResult.Failure(
                    rawResponse = rawResponse,
                    errorSummary = error,
                    feedbackMessage = "Your previous response failed validation: $error. Return corrected JSON only.",
                )
            }
        }

        val value = try {
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

    override fun generateSchema(type: KType): String = objectMapper.writerWithDefaultPrettyPrinter()
        .writeValueAsString(schemaForType(type))

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

    // -----------------------------------------------------------------------
    // Schema generation
    // -----------------------------------------------------------------------

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

    /**
     * Dispatch to Kotlin or JavaBean schema generation based on the
     * runtime class metadata.
     */
    private fun objectSchema(
        type: KClass<*>,
        targetType: KType,
    ): Map<String, Any?> {
        return if (type.isKotlinClass()) {
            kotlinObjectSchema(type, targetType)
        } else {
            val javaType = objectMapper.typeFactory.constructType(targetType.javaType)
            javaBeanObjectSchema(javaType, targetType.isMarkedNullable, JavaBeanSchemaContext())
        }
    }

    // -- Kotlin path (unchanged) --

    private fun kotlinObjectSchema(
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

    // -- JavaBean path (new) --

    /**
     * Internal model for a single JavaBean output property.
     */
    private data class JavaBeanProperty(
        val name: String,
        val type: JavaType,
        val required: Boolean,
        val description: AiDescription?,
        val range: AiRange?,
        val minItems: AiMinItems?,
        val isPrimitive: Boolean,
        val read: (Any) -> Any?,
    )

    /**
     * Active-recursion-path context for JavaBean schema generation.
     *
     * Uses a stack (add/remove) so sibling properties of the same type
     * both get complete schemas, while genuinely recursive types
     * (A → List<A>) are detected and rejected with a controlled error.
     */
    private class JavaBeanSchemaContext {
        val activeTypes: MutableSet<Class<*>> = mutableSetOf()
    }

    private fun javaBeanObjectSchema(
        javaType: JavaType,
        nullable: Boolean,
        context: JavaBeanSchemaContext,
    ): Map<String, Any?> {
        val rawClass = javaType.rawClass
        require(context.activeTypes.add(rawClass)) {
            "Recursive JavaBean structured output type is unsupported: ${rawClass.name}"
        }

        return try {
            val properties = javaBeanProperties(javaType)

            val schemaProperties = linkedMapOf<String, Any?>()
            val required = mutableListOf<String>()

            properties.forEach { prop ->
                schemaProperties[prop.name] = javaPropertySchema(prop, context)
                if (prop.required) {
                    required += prop.name
                }
            }

            linkedMapOf<String, Any?>(
                "type" to "object",
                "properties" to schemaProperties,
                "required" to required,
                "additionalProperties" to false,
            ).also { schema ->
                if (nullable) {
                    schema["nullable"] = true
                }
            }
        } finally {
            context.activeTypes.remove(rawClass)
        }
    }

    private fun javaPropertySchema(
        prop: JavaBeanProperty,
        context: JavaBeanSchemaContext,
    ): Map<String, Any?> {
        val schema = schemaForJavaType(prop.type, context).toMutableMap()
        prop.description?.let { schema["description"] = it.value }
        prop.range?.let {
            schema["minimum"] = it.min
            schema["maximum"] = it.max
        }
        prop.minItems?.let {
            schema["minItems"] = it.value
        }
        return schema
    }

    /**
     * Discovers JavaBean properties from Jackson's deserialization introspection.
     *
     * Only properties that are both writable (setter or writable field)
     * AND readable (getter or readable field) are included.
     * Getter-only calculated and setter-only write-only properties are excluded.
     */
    private fun javaBeanProperties(javaType: JavaType): List<JavaBeanProperty> {
        val beanDescription =
            objectMapper.deserializationConfig.introspect(javaType)

        return beanDescription.findProperties()
            .filter { it.couldDeserialize() }
            .filterNot { it.name == "class" }
            // Exclude write-only (setter-only) properties that validation cannot read
            .filter { it.getter != null || it.field != null }
            .sortedBy { it.name }
            .map { property ->
                val propType = resolveJavaPropertyType(property)
                JavaBeanProperty(
                    name = property.name,
                    type = propType,
                    required = true, // every discovered JavaBean property is required
                    description = findJavaAnnotation(property, AiDescription::class.java),
                    range = findJavaAnnotation(property, AiRange::class.java),
                    minItems = findJavaAnnotation(property, AiMinItems::class.java),
                    isPrimitive = propType.rawClass.isPrimitive,
                    read = { target -> javaPropertyRead(target, property) },
                )
            }
    }

    /**
     * Resolves the [JavaType] for a discovered bean property.
     *
     * Priority: setter parameter → backing field → constructor parameter → getter return type.
     */
    private fun resolveJavaPropertyType(property: BeanPropertyDefinition): JavaType {
        return when {
            property.setter != null ->
                property.setter.getParameterType(0)
            property.field != null ->
                property.field.type
            property.constructorParameter != null ->
                property.constructorParameter.type
            property.getter != null ->
                property.getter.type
            else ->
                error("No resolvable type for property '${property.name}'")
        }
    }

    /**
     * Reads a property value from a JavaBean instance through its getter
     * or accessible field, using Java reflection to handle package-private
     * classes that Jackson's AnnotatedMethod.getValue() cannot access.
     */
    private fun javaPropertyRead(target: Any, property: BeanPropertyDefinition): Any? {
        val getter = property.getter
        if (getter != null) {
            val method = getter.annotated
            if (method != null) {
                method.isAccessible = true
                return method.invoke(target)
            }
            return getter.getValue(target)
        }
        val field = property.field
        if (field != null) {
            val jField = field.annotated
            if (jField != null) {
                jField.isAccessible = true
                return jField.get(target)
            }
            return field.getValue(target)
        }
        return null
    }

    /**
     * Parallel to [schemaForType] for Jackson [JavaType] instances used in the
     * JavaBean path.
     */
    private fun schemaForJavaType(
        javaType: JavaType,
        context: JavaBeanSchemaContext,
    ): Map<String, Any?> {
        val rawClass = javaType.rawClass

        return when {
            rawClass == String::class.java || rawClass == CharSequence::class.java ->
                linkedMapOf("type" to "string")
            rawClass == Int::class.java || rawClass == Long::class.java ||
                rawClass == Short::class.java || rawClass == java.lang.Integer::class.java ||
                rawClass == java.lang.Long::class.java || rawClass == java.lang.Short::class.java ||
                rawClass == Byte::class.java || rawClass == java.lang.Byte::class.java ->
                linkedMapOf("type" to "integer")
            rawClass == Float::class.java || rawClass == Double::class.java ||
                rawClass == java.lang.Float::class.java || rawClass == java.lang.Double::class.java ->
                linkedMapOf("type" to "number")
            rawClass == Boolean::class.java || rawClass == java.lang.Boolean::class.java ->
                linkedMapOf("type" to "boolean")
            rawClass == java.util.Map::class.java || rawClass == java.util.HashMap::class.java ->
                error("Unsupported structured output type: $javaType")
            java.util.Collection::class.java.isAssignableFrom(rawClass) ||
                java.lang.Iterable::class.java.isAssignableFrom(rawClass) -> {
                val itemType = javaType.contentType
                    ?: error("List structured output type must declare an item type: $javaType")
                val items = schemaForJavaType(itemType, context)
                linkedMapOf("type" to "array", "items" to items)
            }
            isJavaLangObject(rawClass) ->
                // Use the shared active-path context for recursion detection
                javaBeanObjectSchema(javaType, nullable = false, context)
            else ->
                error("Unsupported structured output type: $javaType")
        }
    }

    /**
     * A POJO (non-primitive, non-collection, non-map user-defined type) is
     * recognised as any class that is deserializable by Jackson into an object.
     */
    private fun isJavaLangObject(rawClass: Class<*>): Boolean {
        return rawClass != Any::class.java &&
            !rawClass.isEnum &&
            !rawClass.isPrimitive &&
            rawClass.name.startsWith("java.").not() &&
            rawClass.name.startsWith("javax.").not() &&
            rawClass.name.startsWith("jakarta.").not()
    }

    // -----------------------------------------------------------------------
    // Annotation lookup for JavaBean properties
    // -----------------------------------------------------------------------

    /**
     * Searches for a TramAI annotation on a JavaBean property.
     *
     * Order: backing field → setter parameter → constructor parameter
     * → getter or setter method.
     *
     * Backing fields are the primary target because TramAI's
     * `@Target(FIELD)` annotations are placed on Java fields.
     * Setter parameters are checked next, corresponding to
     * `AnnotationTarget.VALUE_PARAMETER`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <A : Annotation> findJavaAnnotation(
        property: BeanPropertyDefinition,
        annotationType: Class<A>,
    ): A? {
        // 1. Backing field (primary target)
        property.field?.let { field ->
            field.getAnnotation(annotationType)?.let { return it }
        }
        // 2. Setter parameter (VALUE_PARAMETER target)
        property.setter?.let { setter ->
            val method = setter.annotated
            if (method != null && method.parameterCount > 0) {
                val paramAnnotations = method.parameterAnnotations
                if (paramAnnotations.isNotEmpty()) {
                    for (ann in paramAnnotations[0]) {
                        if (annotationType.isInstance(ann)) return ann as A
                    }
                }
            }
        }
        // 3. Constructor parameter
        property.constructorParameter?.let { param ->
            try {
                param.getAnnotation(annotationType)?.let { return it }
            } catch (_: Exception) {
                // Fall through
            }
        }
        // 4. Getter method
        property.getter?.let { getter ->
            getter.getAnnotation(annotationType)?.let { return it }
        }
        // 5. Setter method
        property.setter?.let { setter ->
            setter.getAnnotation(annotationType)?.let { return it }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Pre-deserialisation shape validation (catches missing primitive keys)
    // -----------------------------------------------------------------------

    /**
     * Validates the raw JSON tree against the JavaBean schema *before*
     * deserialization. This catches missing required primitive fields
     * (e.g. missing int/double/boolean) which can never be null after
     * deserialization and therefore cannot be detected post-hoc.
     */
    private fun validateJavaBeanJsonShape(
        node: JsonNode,
        javaType: JavaType,
        path: String,
    ): String? {
        if (!node.isObject) return null

        val properties = javaBeanProperties(javaType)

        for (prop in properties) {
            val propPath = if (path.isEmpty()) "'${prop.name}'" else "$path.'${prop.name}'"
            val fieldNode = node.get(prop.name)

            if (fieldNode == null || fieldNode.isNull) {
                return "Property $propPath is required"
            }

            // Recurse into nested objects
            if (isJavaLangObject(prop.type.rawClass)) {
                if (fieldNode.isObject) {
                    val nestedJavaType = objectMapper.typeFactory.constructType(prop.type.rawClass)
                    validateJavaBeanJsonShape(fieldNode, nestedJavaType, propPath)?.let { return it }
                }
            }

            // Recurse into collection item objects
            if (java.util.Collection::class.java.isAssignableFrom(prop.type.rawClass) ||
                java.lang.Iterable::class.java.isAssignableFrom(prop.type.rawClass)
            ) {
                val itemType = prop.type.contentType
                if (itemType != null && fieldNode.isArray) {
                    for (i in 0 until fieldNode.size()) {
                        val itemNode = fieldNode[i]
                        if (itemNode.isObject) {
                            validateJavaBeanJsonShape(itemNode, itemType, "$propPath[$i]")?.let { return it }
                        }
                    }
                }
            }
        }

        return null
    }

    // -----------------------------------------------------------------------
    // Post-deserialisation validation
    // -----------------------------------------------------------------------

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
            is KClass<*> -> {
                val klass = classifier as KClass<*>
                if (klass.isKotlinClass()) {
                    validateObject(value, klass)
                } else {
                    validateJavaBean(value, klass)
                }
            }
            else -> null
        }
    }

    // -- Kotlin validation path (unchanged) --

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

    // -- JavaBean validation path --

    private fun validateJavaBean(
        value: Any,
        type: KClass<*>,
    ): String? {
        val javaType = objectMapper.typeFactory.constructType(type.java)
        return validateJavaBean(value, javaType)
    }

    /**
     * Recursive JavaBean value validator that mirrors [schemaForJavaType].
     *
     * Validates scalars, collections (every item recursively), JavaBeans
     * (every property recursively), and null items in collection elements.
     */
    private fun validateJavaBean(
        value: Any,
        javaType: JavaType,
    ): String? {
        val properties = javaBeanProperties(javaType)

        for (prop in properties) {
            val propValue = prop.read(value)

            validateJavaValue(
                value = propValue,
                javaType = prop.type,
                path = "'${prop.name}'",
                required = prop.required,
                range = prop.range,
                minItems = prop.minItems,
            )?.let { return it }
        }

        return null
    }

    /**
     * Unified recursive validator that mirrors [schemaForJavaType].
     *
     * Handles null checks, scalars, collections (recursive item validation),
     * JavaBeans (recursive property validation), and maps (unsupported).
     */
    private fun validateJavaValue(
        value: Any?,
        javaType: JavaType,
        path: String,
        required: Boolean,
        range: AiRange?,
        minItems: AiMinItems?,
    ): String? {
        val rawClass = javaType.rawClass

        // Null check
        if (value == null) {
            if (required) {
                return "Property $path must not be null"
            }
            return null
        }

        // @AiRange validation (safe for double because deserialised primitives are never null)
        range?.let { r ->
            val numericValue = value as? Number
                ?: return "Property $path must be numeric for @AiRange"
            val asDouble = numericValue.toDouble()
            if (asDouble < r.min || asDouble > r.max) {
                return "Property $path must be between ${r.min} and ${r.max}"
            }
        }

        // @AiMinItems validation
        minItems?.let { mi ->
            val collectionValue = value as? Collection<*>
                ?: return "Property $path must be a collection for @AiMinItems"
            if (collectionValue.size < mi.value) {
                return "Property $path must contain at least ${mi.value} items"
            }
        }

        // Scalars — validation complete
        if (isScalarJavaType(rawClass)) return null

        // Maps — unsupported
        if (java.util.Map::class.java.isAssignableFrom(rawClass)) {
            return "Unsupported structured output type: $javaType"
        }

        // Collections / iterables — validate every item recursively
        if (java.util.Collection::class.java.isAssignableFrom(rawClass) ||
            java.lang.Iterable::class.java.isAssignableFrom(rawClass)
        ) {
            val itemType = javaType.contentType ?: return null
            val items = when (value) {
                is List<*> -> value
                is Collection<*> -> value.toList()
                is Iterable<*> -> value.toList()
                else -> return null
            }
            items.forEachIndexed { index, item ->
                // Null items in collections fail (required by schema)
                if (item == null) {
                    return "Item $index of $path must not be null"
                }
                validateJavaValue(
                    value = item,
                    javaType = itemType,
                    path = "$path[$index]",
                    required = true,
                    range = null,
                    minItems = null,
                )?.let { return it }
            }
            return null
        }

        // JavaBean — validate every property recursively
        if (isJavaLangObject(rawClass)) {
            val properties = javaBeanProperties(
                objectMapper.typeFactory.constructType(rawClass)
            )
            for (prop in properties) {
                val propValue = prop.read(value)
                val propPath = "$path.'${prop.name}'"
                propValue?.let { pv ->
                    validateJavaValue(
                        value = pv,
                        javaType = prop.type,
                        path = propPath,
                        required = prop.required,
                        range = prop.range,
                        minItems = prop.minItems,
                    )?.let { return it }
                }
            }
            return null
        }

        return null
    }

    private fun isScalarJavaType(rawClass: Class<*>): Boolean {
        return rawClass == String::class.java ||
            rawClass == CharSequence::class.java ||
            rawClass == Int::class.java || rawClass == java.lang.Integer::class.java ||
            rawClass == Long::class.java || rawClass == java.lang.Long::class.java ||
            rawClass == Short::class.java || rawClass == java.lang.Short::class.java ||
            rawClass == Byte::class.java || rawClass == java.lang.Byte::class.java ||
            rawClass == Float::class.java || rawClass == java.lang.Float::class.java ||
            rawClass == Double::class.java || rawClass == java.lang.Double::class.java ||
            rawClass == Boolean::class.java || rawClass == java.lang.Boolean::class.java
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Detects whether [this] class was compiled by Kotlin by checking for the
     * `kotlin.Metadata` annotation on the underlying Java class.
     */
    private fun KClass<*>.isKotlinClass(): Boolean =
        java.getAnnotation(kotlin.Metadata::class.java) != null

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
