package dev.tramai.structured.descriptor

/**
 * Translates a compiled [StructuredTypeDescriptor] into the JSON-schema map
 * that the handler serializes. Pure descriptor → schema; no reflection, no
 * Jackson introspection, no annotation lookup, no [kotlin.reflect.KType] or
 * [com.fasterxml.jackson.databind.JavaType] dispatch.
 */
internal class StructuredSchemaRenderer {

    fun render(descriptor: StructuredTypeDescriptor): Map<String, Any?> = when (descriptor) {
        is StructuredTypeDescriptor.Scalar -> scalarSchema(descriptor)
        is StructuredTypeDescriptor.Enum -> enumSchema(descriptor)
        is StructuredTypeDescriptor.Collection -> collectionSchema(descriptor)
        is StructuredTypeDescriptor.Object -> objectSchema(descriptor)
    }

    fun renderProperty(property: StructuredPropertyDescriptor): Map<String, Any?> {
        val schema = render(property.type).toMutableMap()
        property.description?.let { schema["description"] = it }
        property.range?.let {
            schema["minimum"] = it.min
            schema["maximum"] = it.max
        }
        return schema
    }

    private fun scalarSchema(descriptor: StructuredTypeDescriptor.Scalar): Map<String, Any?> {
        val type = when (descriptor.kind) {
            ScalarKind.STRING -> "string"
            ScalarKind.INTEGER -> "integer"
            ScalarKind.NUMBER -> "number"
            ScalarKind.BOOLEAN -> "boolean"
        }
        return linkedMapOf<String, Any?>(
            "type" to type,
        ).also { schema ->
            if (descriptor.nullable) {
                schema["nullable"] = true
            }
        }
    }

    private fun enumSchema(descriptor: StructuredTypeDescriptor.Enum): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "type" to "string",
            "enum" to descriptor.values,
        ).also { schema ->
            if (descriptor.nullable) {
                schema["nullable"] = true
            }
        }

    private fun collectionSchema(descriptor: StructuredTypeDescriptor.Collection): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "type" to "array",
            "items" to render(descriptor.item),
        ).also { schema ->
            descriptor.minItems?.let { schema["minItems"] = it }
            if (descriptor.nullable) {
                schema["nullable"] = true
            }
        }

    private fun objectSchema(descriptor: StructuredTypeDescriptor.Object): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>()
        val required = buildList {
            descriptor.properties.forEach { property ->
                properties[property.name] = renderProperty(property)
                if (property.required) {
                    add(property.name)
                }
            }
        }

        return linkedMapOf<String, Any?>(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to false,
        ).also { schema ->
            if (descriptor.nullable) {
                schema["nullable"] = true
            }
        }
    }
}
