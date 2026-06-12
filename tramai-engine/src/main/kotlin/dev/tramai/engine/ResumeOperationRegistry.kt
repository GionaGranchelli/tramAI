package dev.tramai.engine

import dev.tramai.core.exception.ConfigurationException
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
 * Uses copy-on-write semantics under a write lock for atomic multi-operation
 * registration. Readers observe the registry as one state transition.
 *
 * Rules:
 * - Missing key: ConfigurationException("resume-operation-not-registered")
 * - Same key + same digest: idempotent (allowed)
 * - Same key + different digest: fail closed with ConfigurationException
 */
internal class ResumeOperationRegistry {

    @Volatile
    private var operations: Map<RegistryKey, RegisteredResumeOperation> = emptyMap()
    private val lock = ReentrantReadWriteLock()

    private inline fun <T> withReadLock(action: () -> T): T {
        lock.readLock().lock()
        try { return action() } finally { lock.readLock().unlock() }
    }

    private inline fun <T> withWriteLock(action: () -> T): T {
        lock.writeLock().lock()
        try { return action() } finally { lock.writeLock().unlock() }
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

        val existing = operations[key]
        when {
            existing == null -> {
                val updated = operations.toMutableMap()
                updated[key] = RegisteredResumeOperation(
                    reference = reference,
                    serviceDefinition = serviceDefinition,
                    operation = operation,
                    handler = handler,
                )
                operations = updated.toMap()
            }
            existing.reference.resumeDefinitionDigest != reference.resumeDefinitionDigest ->
                throw ConfigurationException("resume-operation-registration-conflict")
            // else idempotent
        }

        reference
    }

    /**
     * Atomically register all operations for a service definition.
     *
     * Validates all operations for conflicts first, then publishes all as one
     * atomic state transition. A conflicting operation prevents the entire
     * batch from being published.
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

        // Phase 1: Validate all conflicts before any mutation
        entries.forEach { (key, incoming) ->
            val existing = operations[key]
            require(
                existing == null ||
                    existing.reference.resumeDefinitionDigest == incoming.reference.resumeDefinitionDigest
            ) { "resume-operation-registration-conflict" }
        }

        // Phase 2: Atomic publish via copy-on-write
        val updated = operations.toMutableMap()
        entries.forEach { (key, entry) ->
            updated.putIfAbsent(key, entry)
        }
        operations = updated.toMap()
    }

    /**
     * Resolve a [ResumeOperationReference] to its registered operation.
     *
     * Uses a read lock to ensure visibility of a fully published registry state.
     *
     * @throws ConfigurationException if the operation is not registered or has drifted.
     */
    fun resolve(reference: ResumeOperationReference): RegisteredResumeOperation = withReadLock {
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

        registered
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
