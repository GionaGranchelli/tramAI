package dev.tramai.structured.descriptor

import java.security.MessageDigest

/**
 * Computes a stable, deterministic SHA-256 fingerprint of a compiled
 * [StructuredTypeDescriptor].
 *
 * The canonical representation is built by a dedicated walk (not by
 * serializing the descriptor data classes), so fingerprint stability never
 * depends on incidental serialization details. Runtime accessors are
 * excluded: two handlers compiling the same type from different languages
 * produce the same fingerprint as long as the semantic contract matches.
 *
 * Property order and enum value order follow the compiled descriptor, which
 * is deterministically sorted by both compilers (alphabetical property
 * names, declaration order for enum constants).
 */
internal class StructuredContractFingerprint {

    fun fingerprint(descriptor: StructuredTypeDescriptor): String {
        val canonical = buildString {
            appendDescriptor(this, descriptor)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun appendDescriptor(sb: StringBuilder, descriptor: StructuredTypeDescriptor) {
        when (descriptor) {
            is StructuredTypeDescriptor.Scalar -> {
                sb.append("scalar(").append(descriptor.kind.name.lowercase()).append(')')
            }
            is StructuredTypeDescriptor.Enum -> {
                sb.append("enum[")
                descriptor.values.forEachIndexed { index, value ->
                    if (index > 0) sb.append(',')
                    sb.append(escape(value))
                }
                sb.append(']')
            }
            is StructuredTypeDescriptor.Collection -> {
                sb.append("collection(")
                descriptor.minItems?.let { sb.append("minItems=").append(it).append(',') }
                appendDescriptor(sb, descriptor.item)
                sb.append(')')
            }
            is StructuredTypeDescriptor.Object -> {
                // typeName deliberately excluded: it is compiler/diagnostic
                // metadata, not part of the JSON contract. Equivalent Kotlin
                // and JavaBean DTOs have different class names but the same
                // semantic contract, so they must fingerprint identically.
                sb.append("object{")
                descriptor.properties.forEachIndexed { index, property ->
                    if (index > 0) sb.append(';')
                    appendProperty(sb, property)
                }
                sb.append('}')
            }
        }
        sb.append(if (descriptor.nullable) "?n" else "?nn")
    }

    private fun appendProperty(sb: StringBuilder, property: StructuredPropertyDescriptor) {
        sb.append(escape(property.name)).append('=')
        property.description?.let { sb.append("desc=").append(escape(it)).append(',') }
        property.range?.let { sb.append("range=").append(it.min).append("..").append(it.max).append(',') }
        appendDescriptor(sb, property.type)
        sb.append(if (property.required) ";req" else ";opt")
    }

    private fun escape(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
        append('"')
    }
}
