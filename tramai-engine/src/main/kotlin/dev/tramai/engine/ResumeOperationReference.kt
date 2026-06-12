package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest
import java.lang.reflect.Method

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
        else -> "L" + type.canonicalName.replace('.', '/') + ";"
    }
}
