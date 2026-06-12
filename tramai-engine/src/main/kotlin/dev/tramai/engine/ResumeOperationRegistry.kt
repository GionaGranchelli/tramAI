package dev.tramai.engine

import dev.tramai.core.exception.ConfigurationException
import java.util.concurrent.ConcurrentHashMap

/**
 * A registered resume-able operation in the trusted [ResumeOperationRegistry].
 *
 * @property reference Stable data-only reference that identifies this operation.
 * @property serviceDefinition The service definition that created this operation.
 * @property operation The resolved operation definition.
 * @property handler The invocation handler that can execute this operation.
 */
internal data class RegisteredResumeOperation(
    val reference: ResumeOperationReference,
    val serviceDefinition: ServiceDefinition,
    val operation: OperationDefinition,
    val handler: TramaiInvocationHandler,
)

/**
 * Thread-safe, trusted registry of resume-able operations.
 *
 * Operations are registered when a service is created (via [TramaiEngine.create])
 * or explicitly registered (via [TramaiEngine.registerService]).
 *
 * Rules:
 * - Missing key: ConfigurationException("resume-operation-not-registered")
 * - Same key + same digest: idempotent (allowed)
 * - Same key + different digest: fail closed with ConfigurationException
 */
internal class ResumeOperationRegistry {

    private val operations = ConcurrentHashMap<RegistryKey, RegisteredResumeOperation>()

    /**
     * Register or verify an operation for resume.
     *
     * @throws ConfigurationException if the key already exists with a different digest.
     */
    fun register(
        serviceDefinition: ServiceDefinition,
        operation: OperationDefinition,
        handler: TramaiInvocationHandler,
    ): ResumeOperationReference {
        val key = createKey(serviceDefinition, operation)
        val reference = ResumeOperationReference(
            serviceInterface = key.serviceInterface,
            methodName = key.methodName,
            jvmMethodDescriptor = key.jvmMethodDescriptor,
            resumeDefinitionDigest = ResumeDefinitionDigestHelper.compute(serviceDefinition, operation),
        )

        operations.compute(key) { _, existing ->
            when {
                existing == null -> RegisteredResumeOperation(
                    reference = reference,
                    serviceDefinition = serviceDefinition,
                    operation = operation,
                    handler = handler,
                )
                existing.reference.resumeDefinitionDigest == reference.resumeDefinitionDigest -> {
                    // Idempotent — same definition, same digest. Return existing.
                    existing
                }
                else -> throw ConfigurationException(
                    "Resume operation registration conflict for " +
                        "${key.serviceInterface}.${key.methodName}: " +
                        "existing digest ${existing.reference.resumeDefinitionDigest.value} != " +
                        "new digest ${reference.resumeDefinitionDigest.value}"
                )
            }
        }

        return reference
    }

    /**
     * Resolve a [ResumeOperationReference] to its registered operation.
     *
     * @throws ConfigurationException if the operation is not registered or has drifted.
     */
    fun resolve(reference: ResumeOperationReference): RegisteredResumeOperation {
        val key = RegistryKey(
            serviceInterface = reference.serviceInterface,
            methodName = reference.methodName,
            jvmMethodDescriptor = reference.jvmMethodDescriptor,
        )
        val registered = operations[key]
            ?: throw ConfigurationException("resume-operation-not-registered: " +
                "${reference.serviceInterface}.${reference.methodName}")

        if (registered.reference.resumeDefinitionDigest != reference.resumeDefinitionDigest) {
            throw ConfigurationException("resume-operation-definition-drift: " +
                "${reference.serviceInterface}.${reference.methodName}: " +
                "stored digest ${reference.resumeDefinitionDigest.value} != " +
                "registered digest ${registered.reference.resumeDefinitionDigest.value}")
        }

        return registered
    }

    private fun createKey(
        serviceDefinition: ServiceDefinition,
        operation: OperationDefinition,
    ): RegistryKey = RegistryKey(
        serviceInterface = serviceDefinition.serviceType.qualifiedName
            ?: serviceDefinition.serviceType.simpleName.orEmpty(),
        methodName = operation.method.name,
        jvmMethodDescriptor = JvmMethodDescriptorHelper.compute(operation.method),
    )

    private data class RegistryKey(
        val serviceInterface: String,
        val methodName: String,
        val jvmMethodDescriptor: String,
    )
}
