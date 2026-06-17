package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.security.audit.AuditStore

/**
 * Default implementation of [SovereignRuntimeOperations].
 *
 * Checks which store beans are available in the Spring context and
 * detects persistence mode (file-backed vs in-memory) based on
 * simple class-name heuristics.
 *
 * Does NOT depend on optional file-persistence module.
 */
class DefaultSovereignRuntimeOperations(
    private val auditStore: AuditStore?,
    private val approvalStore: ApprovalStore?,
    private val approvalContinuationStore: ApprovalContinuationStore?,
    private val suspendedInvocationStore: SuspendedInvocationStore?,
) : SovereignRuntimeOperations {

    override fun status(): SovereignRuntimeStatus {
        val persistenceMode = detectPersistenceMode()

        return SovereignRuntimeStatus(
            runtimeAvailable = true,
            auditStoreAvailable = auditStore != null,
            approvalStoreAvailable = approvalStore != null,
            approvalContinuationStoreAvailable = approvalContinuationStore != null,
            suspendedInvocationStoreAvailable = suspendedInvocationStore != null,
            persistenceMode = persistenceMode,
        )
    }

    private fun detectPersistenceMode(): String {
        listOfNotNull(auditStore, approvalStore, approvalContinuationStore, suspendedInvocationStore)
            .forEach { store ->
                val name = store.javaClass.name
                if (name.contains("File", ignoreCase = true) ||
                    name.startsWith("dev.tramai.persistence.file")
                ) {
                    return "file"
                }
            }
        return "memory"
    }
}
