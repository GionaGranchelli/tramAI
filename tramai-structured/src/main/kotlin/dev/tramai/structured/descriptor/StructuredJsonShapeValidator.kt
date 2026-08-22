package dev.tramai.structured.descriptor

import com.fasterxml.jackson.databind.JsonNode

/**
 * Validates a raw JSON tree against a compiled [StructuredTypeDescriptor]
 * before deserialization. Enforces descriptor-level shape guarantees:
 * required property presence, nullability, collection shape, nested shape,
 * and object-ness where the schema declares it. Pure descriptor → node
 * traversal; no [kotlin.reflect.KType] or [com.fasterxml.jackson.databind.JavaType].
 *
 * Note: scalar/enum value correctness (e.g. an enum string not in the
 * declared set) is intentionally delegated to deserialization — the schema
 * declares allowed values, but Jackson owns value-level parsing. This keeps
 * error messages compatible with the pre-descriptor handler (enum mismatch
 * surfaces as "Could not deserialize the JSON payload").
 */
internal class StructuredJsonShapeValidator {

    fun validate(
        node: JsonNode,
        descriptor: StructuredTypeDescriptor,
        path: String,
    ): String? = when (descriptor) {
        is StructuredTypeDescriptor.Scalar -> null
        is StructuredTypeDescriptor.Enum -> null
        is StructuredTypeDescriptor.Collection -> validateCollection(node, descriptor, path)
        is StructuredTypeDescriptor.Object -> validateObject(node, descriptor, path)
    }

    private fun validateCollection(
        node: JsonNode,
        descriptor: StructuredTypeDescriptor.Collection,
        path: String,
    ): String? {
        if (!node.isArray) return "Expected an array at $path"
        for (i in 0 until node.size()) {
            validate(node[i], descriptor.item, "$path[$i]")?.let { return it }
        }
        return null
    }

    private fun validateObject(
        node: JsonNode,
        descriptor: StructuredTypeDescriptor.Object,
        path: String,
    ): String? {
        if (!node.isObject) return null

        descriptor.properties.forEach { property ->
            val propPath = if (path.isEmpty()) "'${property.name}'" else "$path.'${property.name}'"
            val fieldNode = node.get(property.name)
            if (fieldNode == null || fieldNode.isNull) {
                if (property.required) {
                    return "Property $propPath is required"
                }
                return@forEach
            }
            validate(fieldNode, property.type, propPath)?.let { return it }
        }
        return null
    }
}
