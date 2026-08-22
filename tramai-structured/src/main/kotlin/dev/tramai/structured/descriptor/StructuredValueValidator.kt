package dev.tramai.structured.descriptor

/**
 * Validates a deserialized runtime value against a compiled
 * [StructuredTypeDescriptor]. Owns constraint checks that need runtime values
 * (`@AiRange`, `@AiMinItems`), nested nullability, and recursion through the
 * compiled property accessors. Pure descriptor → value traversal; no second
 * annotation lookup, no [kotlin.reflect.KType] or
 * [com.fasterxml.jackson.databind.JavaType] dispatch.
 */
internal class StructuredValueValidator {

    fun validate(
        value: Any?,
        descriptor: StructuredTypeDescriptor,
        path: String,
    ): String? {
        if (value == null) {
            val where = if (path.isEmpty()) "" else " at $path"
            return if (descriptor.nullable) null else "Value$where must not be null"
        }

        return when (descriptor) {
            is StructuredTypeDescriptor.Scalar -> null
            is StructuredTypeDescriptor.Enum -> null
            is StructuredTypeDescriptor.Collection -> validateCollection(value, descriptor, path)
            is StructuredTypeDescriptor.Object -> validateObject(value, descriptor, path)
        }
    }

    private fun validateCollection(
        value: Any,
        descriptor: StructuredTypeDescriptor.Collection,
        path: String,
    ): String? {
        descriptor.minItems?.let { minItems ->
            val collectionValue = value as? Collection<*>
                ?: return "Property $path must be a collection for @AiMinItems"
            if (collectionValue.size < minItems) {
                return "Property $path must contain at least $minItems items"
            }
        }

        val items = when (value) {
            is List<*> -> value
            is Collection<*> -> value.toList()
            else -> return null
        }
        items.forEachIndexed { index, item ->
            // Delegate item validation so descriptor nullability applies: a null
            // item in a nullable item collection (List<String?>) is legal, while
            // a null item in a non-nullable collection still fails.
            validate(item, descriptor.item, "$path[$index]")?.let { return it }
        }
        return null
    }

    private fun validateObject(
        value: Any,
        descriptor: StructuredTypeDescriptor.Object,
        path: String,
    ): String? {
        descriptor.properties.forEach { property ->
            val propPath = if (path.isEmpty()) "'${property.name}'" else "$path.'${property.name}'"
            val propValue = property.accessor.read(value)

            if (propValue == null) {
                if (property.required) {
                    return "Property $propPath must not be null"
                }
                return@forEach
            }

            property.range?.let { range ->
                val numericValue = propValue as? Number
                    ?: return "Property $propPath must be numeric for @AiRange"
                val asDouble = numericValue.toDouble()
                if (asDouble < range.min || asDouble > range.max) {
                    return "Property $propPath must be between ${range.min} and ${range.max}"
                }
            }

            validate(propValue, property.type, propPath)?.let { return it }
        }
        return null
    }
}
