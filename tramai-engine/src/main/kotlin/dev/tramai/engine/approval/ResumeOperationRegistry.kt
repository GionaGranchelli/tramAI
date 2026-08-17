package dev.tramai.engine.approval

import dev.tramai.core.exception.ConfigurationException
import dev.tramai.engine.JvmMethodDescriptorHelper
import dev.tramai.engine.ResumeDefinitionDigestHelper
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.planning.ServiceDefinition
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * A registered resume-able operation in the trusted [ResumeOperationRegistry].
 *
 * @property reference Stable data-only reference that identifies this operation.
 * @property serviceDefinition The service definition that created this operation.
 * @property operation The resolved operation definition.
 * @property resumeExecutor The executor that can execute an already-approved resumed operation.
 */
internal data class RegisteredResumeOperation(
    val reference: ResumeOperationReference,
    val serviceDefinition: ServiceDefinition,
    val operation: OperationDefinition,
    val resumeExecutor: ClaimedResumeExecutor,
)

/** Thread-safe, trusted registry of resume-able operations. */
internal class ResumeOperationRegistry {
    @Volatile private var operations: Map<RegistryKey, RegisteredResumeOperation> = emptyMap()
    private val lock = ReentrantReadWriteLock()
    private inline fun <T> withReadLock(action: () -> T): T { lock.readLock().lock(); try { return action() } finally { lock.readLock().unlock() } }
    private inline fun <T> withWriteLock(action: () -> T): T { lock.writeLock().lock(); try { return action() } finally { lock.writeLock().unlock() } }

    fun register(serviceDefinition: ServiceDefinition, operation: OperationDefinition, resumeExecutor: ClaimedResumeExecutor): ResumeOperationReference = withWriteLock {
        val key = createKey(serviceDefinition, operation)
        val reference = ResumeOperationReference(key.serviceInterface, key.methodName, key.jvmMethodDescriptor, ResumeDefinitionDigestHelper.compute(serviceDefinition, operation))
        val existing = operations[key]
        when {
            existing == null -> operations = operations.toMutableMap().also { it[key] = RegisteredResumeOperation(reference, serviceDefinition, operation, resumeExecutor) }.toMap()
            existing.reference.resumeDefinitionDigest != reference.resumeDefinitionDigest -> throw ConfigurationException("resume-operation-registration-conflict")
        }
        reference
    }

    fun registerAll(serviceDefinition: ServiceDefinition, resumeExecutor: ClaimedResumeExecutor) = withWriteLock {
        val entries = serviceDefinition.operations.entries.map { (_, plan) ->
            val operation = plan.definition; val key = createKey(serviceDefinition, operation)
            val reference = ResumeOperationReference(key.serviceInterface, key.methodName, key.jvmMethodDescriptor, ResumeDefinitionDigestHelper.compute(serviceDefinition, operation))
            key to RegisteredResumeOperation(reference, serviceDefinition, operation, resumeExecutor)
        }
        entries.forEach { (key, incoming) -> require(operations[key] == null || operations[key]!!.reference.resumeDefinitionDigest == incoming.reference.resumeDefinitionDigest) { "resume-operation-registration-conflict" } }
        operations = operations.toMutableMap().also { updated -> entries.forEach { (key, entry) -> updated.putIfAbsent(key, entry) } }.toMap()
    }

    fun resolve(reference: ResumeOperationReference): RegisteredResumeOperation = withReadLock {
        val registered = operations[RegistryKey(reference.serviceInterface, reference.methodName, reference.jvmMethodDescriptor)] ?: throw ConfigurationException("resume-operation-not-registered")
        if (registered.reference.resumeDefinitionDigest != reference.resumeDefinitionDigest) throw ConfigurationException("resume-operation-definition-drift")
        registered
    }
    private fun createKey(serviceDefinition: ServiceDefinition, operation: OperationDefinition) = RegistryKey(serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(), operation.method.name, JvmMethodDescriptorHelper.compute(operation.method))
    private data class RegistryKey(val serviceInterface: String, val methodName: String, val jvmMethodDescriptor: String)
}
