package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.ResolvedTool
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Stable, serializable reference that identifies a resume-able operation.
 *
 * Contains only data — no runtime objects, no reflection objects, no callbacks.
 * Resolved by [ResumeOperationRegistry] at resume time against the current runtime.
 *
 * @property serviceInterface Fully qualified service interface name.
 * @property methodName The method name.
 * @property jvmMethodDescriptor JVM descriptor for unambiguous overload resolution.
 * @property resumeDefinitionDigest Digest of the canonicalised service + operation definition.
 */
data class ResumeOperationReference(
    val serviceInterface: String,
    val methodName: String,
    val jvmMethodDescriptor: String,
    val resumeDefinitionDigest: Sha256Digest,
)

/**
 * Stable, serializable reference that identifies the tool whose execution was suspended.
 *
 * Contains only data — no runtime objects, no reflection objects.
 *
 * @property toolName The name of the tool.
 * @property declarationDigest Digest of the canonicalised tool declaration.
 */
data class ResumeToolReference(
    val toolName: String,
    val declarationDigest: Sha256Digest,
)

/**
 * Helper for computing a deterministic digest over a tool declaration.
 *
 * Canonicalises all tool declaration fields so the digest can be compared
 * at resume time to detect tool definition drift.
 */
internal object ResumeToolDeclarationDigestHelper {
    fun compute(tool: ResolvedTool): Sha256Digest {
        val canonical = buildString {
            appendField("name", tool.name)
            appendField("description", tool.description)
            appendField("schema", tool.inputSchemaJson)
            append("idempotent=").append(tool.idempotent).append('\n')
            append("side_effect_level=").append(tool.sideEffectLevel?.name ?: "null").append('\n')
            val sec = tool.security
            if (sec != null) {
                appendField("permission", sec.permission)
                appendField("risk", sec.risk?.name ?: "null")
                appendField("approval", sec.approval?.name ?: "null")
                appendField("network_egress", sec.managedNetworkEgress?.name ?: "null")
                appendField("audit", sec.audit?.name ?: "null")
                appendField("compat_mode", sec.compatibilityMode?.name ?: "null")
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }
}

/**
 * Helper for computing a deterministic JVM method descriptor from a [java.lang.reflect.Method].
 *
 * Describes parameter types and return type in JVM internal form so that
 * overloaded methods resolve unambiguously. Does NOT store Method, KClass, KType, or KFunction.
 */
internal object JvmMethodDescriptorHelper {

    fun compute(method: Method): String {
        val params = method.parameterTypes.joinToString(separator = "") { typeDescriptor(it) }
        val ret = typeDescriptor(method.returnType)
        return "($params)$ret"
    }

    private fun typeDescriptor(type: Class<*>): String = when {
        type == java.lang.Void.TYPE -> "V"
        type == java.lang.Boolean.TYPE -> "Z"
        type == java.lang.Byte.TYPE -> "B"
        type == java.lang.Character.TYPE -> "C"
        type == java.lang.Short.TYPE -> "S"
        type == java.lang.Integer.TYPE -> "I"
        type == java.lang.Long.TYPE -> "J"
        type == java.lang.Float.TYPE -> "F"
        type == java.lang.Double.TYPE -> "D"
        type.isArray -> "[" + typeDescriptor(type.componentType)
        else -> "L" + type.name.replace('.', '/') + ";"
    }
}
