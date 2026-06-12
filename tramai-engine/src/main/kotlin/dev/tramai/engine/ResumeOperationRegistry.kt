package dev.tramai.engine

import dev.tramai.core.exception.ConfigurationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

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
    private val registrationLock = ReentrantReadWriteLock()

    private inline fun <T> withWriteLock(action: () -> T): T {
        registrationLock.writeLock().lock()
        try { return action() } finally { registrationLock.writeLock().unlock() }
    }

    /**
     * Register or verify an operation for resume.
     *
     * @throws ConfigurationException if the key already exists with a different digest.
     */
    fun register(
        serviceDefinition: ServiceDefinition,
        operation: OperationDefinition,
        handler: TramaiInvocationHandler,
    ): ResumeOperationReference = withWriteLock {
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
                else -> throw ConfigurationException("resume-operation-registration-conflict")
            }
        }

        reference
    }

    /**
     * Atomically register all operations for a service definition.
     *
     * Validates all operations for conflicts first, then publishes them.
     * This prevents partial registration when one operation conflicts.
     *
     * @throws ConfigurationException if any key already exists with a different digest.
     */
    fun registerAll(
        serviceDefinition: ServiceDefinition,
        handler: TramaiInvocationHandler,
    ) = withWriteLock {
        val entries = serviceDefinition.operations.entries.map { (_, operation) ->
            val key = createKey(serviceDefinition, operation)
            val reference = ResumeOperationReference(
                serviceInterface = key.serviceInterface,
                methodName = key.methodName,
                jvmMethodDescriptor = key.jvmMethodDescriptor,
                resumeDefinitionDigest = ResumeDefinitionDigestHelper.compute(serviceDefinition, operation),
            )
            key to RegisteredResumeOperation(
                reference = reference,
                serviceDefinition = serviceDefinition,
                operation = operation,
                handler = handler,
            )
        }

        // Validate all conflicts first, then publish
        entries.forEach { (key, entry) ->
            operations.compute(key) { _, existing ->
                when {
                    existing == null -> entry
                    existing.reference.resumeDefinitionDigest == entry.reference.resumeDefinitionDigest -> existing
                    else -> throw ConfigurationException("resume-operation-registration-conflict")
                }
            }
        }
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
            ?: throw ConfigurationException("resume-operation-not-registered")

        if (registered.reference.resumeDefinitionDigest != reference.resumeDefinitionDigest) {
            throw ConfigurationException("resume-operation-definition-drift")
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
