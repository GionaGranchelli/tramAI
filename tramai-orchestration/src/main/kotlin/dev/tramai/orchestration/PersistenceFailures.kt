package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Machine-readable, stable classification for persistence failures. */
enum class PersistenceFailureCode {
    READ_FAILED,
    WRITE_FAILED,
    DELETE_FAILED,
    LIST_FAILED,
    CONFLICT,
    CORRUPTED_DATA,
}

/** Persistence resource context for a failure. */
enum class PersistenceResourceKind { CHECKPOINT, LEASE, STEP_ATTEMPT, WORKER_REGISTRY }

/** Persistence operation context for a failure. */
enum class PersistenceOperation { LOAD, SAVE, DELETE, LIST, CLAIM, RENEW, RELEASE, COMPARE_AND_SET }

/**
 * Receives diagnostic-only persistence failure data.
 *
 * Delivery is fail-open: ordinary observer failures do not replace the persistence
 * failure. An observer-thrown [CancellationException] is swallowed only while the
 * enclosing coroutine remains active; genuine cancellation is rethrown. Observer
 * data is never automatically forwarded to public exceptions, logs, audit, or
 * telemetry.
 */
fun interface PersistenceFailureDiagnosticObserver {
    suspend fun onFailure(event: PersistenceFailureDiagnosticEvent)
}

data class PersistenceFailureDiagnosticEvent(
    val resourceKind: PersistenceResourceKind,
    val operation: PersistenceOperation,
    val failureCode: PersistenceFailureCode,
    val failure: Throwable,
)

object NoOpPersistenceFailureDiagnosticObserver : PersistenceFailureDiagnosticObserver {
    override suspend fun onFailure(event: PersistenceFailureDiagnosticEvent) = Unit
}

internal class CorruptCheckpointException(
    message: String,
    val rawPayload: String?,
) : RuntimeException(message)

internal class CorruptStepAttemptException(
    message: String,
    val rawPayload: String?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** General-purpose safe persistence failure for non-semantic persistence errors. */
class WorkflowPersistenceFailureException(
    message: String,
) : RuntimeException(message) {
    var failureCode: PersistenceFailureCode? = null
        internal set
    var safeFactoryTrusted: Boolean = false
        internal set
}

internal fun fixedPersistenceFailureMessage(
    resourceKind: PersistenceResourceKind,
    operation: PersistenceOperation,
    code: PersistenceFailureCode,
): String = when (code) {
    PersistenceFailureCode.CONFLICT -> when {
        resourceKind == PersistenceResourceKind.CHECKPOINT &&
            operation in setOf(PersistenceOperation.COMPARE_AND_SET, PersistenceOperation.SAVE) ->
            "Workflow checkpoint conflict"
        resourceKind == PersistenceResourceKind.LEASE -> "Workflow lease conflict"
        else -> "${persistenceResourceLabel(resourceKind).replaceFirstChar { it.uppercase() }} conflict"
    }
    PersistenceFailureCode.CORRUPTED_DATA -> "Persisted ${persistenceResourceLabel(resourceKind)} is invalid"
    PersistenceFailureCode.READ_FAILED -> "Workflow persistence read failed"
    PersistenceFailureCode.WRITE_FAILED -> "Workflow persistence write failed"
    PersistenceFailureCode.DELETE_FAILED -> "Workflow persistence delete failed"
    PersistenceFailureCode.LIST_FAILED -> "Workflow persistence list failed"
}

private fun persistenceResourceLabel(resourceKind: PersistenceResourceKind): String = when (resourceKind) {
    PersistenceResourceKind.CHECKPOINT -> "workflow checkpoint"
    PersistenceResourceKind.LEASE -> "workflow lease"
    PersistenceResourceKind.STEP_ATTEMPT -> "step-attempt record"
    PersistenceResourceKind.WORKER_REGISTRY -> "worker-registry record"
}

internal fun safePersistenceFailure(
    resourceKind: PersistenceResourceKind,
    operation: PersistenceOperation,
    code: PersistenceFailureCode,
): RuntimeException {
    val failure = when {
        resourceKind == PersistenceResourceKind.CHECKPOINT && code == PersistenceFailureCode.CONFLICT ->
            WorkflowCheckpointConflictException(fixedPersistenceFailureMessage(resourceKind, operation, code))
        resourceKind == PersistenceResourceKind.LEASE && code == PersistenceFailureCode.CONFLICT ->
            WorkflowLeaseConflictException(fixedPersistenceFailureMessage(resourceKind, operation, code))
        resourceKind == PersistenceResourceKind.CHECKPOINT && code == PersistenceFailureCode.CORRUPTED_DATA ->
            WorkflowCheckpointCorruptionException(fixedPersistenceFailureMessage(resourceKind, operation, code))
        resourceKind == PersistenceResourceKind.STEP_ATTEMPT && code == PersistenceFailureCode.CORRUPTED_DATA ->
            StepAttemptRecordCorruptionException(fixedPersistenceFailureMessage(resourceKind, operation, code))
        else -> WorkflowPersistenceFailureException(fixedPersistenceFailureMessage(resourceKind, operation, code))
    }
    failure.setPersistenceFailureMetadata(code)
    return failure
}

internal fun safeWorkerObservableFailure(
    resourceKind: PersistenceResourceKind,
    operation: PersistenceOperation,
    error: Throwable,
): Throwable {
    if (error.persistenceFailureTrusted()) return error

    val code = error.persistenceFailureCode() ?: defaultPersistenceFailureCode(operation, error)
    return safePersistenceFailure(resourceKind, operation, code)
}

private fun RuntimeException.setPersistenceFailureMetadata(code: PersistenceFailureCode) {
    when (this) {
        is WorkflowResumeException -> { failureCode = code; safeFactoryTrusted = true }
        is WorkflowCheckpointConflictException -> { failureCode = code; safeFactoryTrusted = true }
        is WorkflowCheckpointCorruptionException -> { failureCode = code; safeFactoryTrusted = true }
        is WorkflowLeaseConflictException -> { failureCode = code; safeFactoryTrusted = true }
        is StaleWorkflowLeaseException -> { failureCode = code; safeFactoryTrusted = true }
        is StepAttemptRecordCorruptionException -> { failureCode = code; safeFactoryTrusted = true }
        is WorkflowPersistenceFailureException -> { failureCode = code; safeFactoryTrusted = true }
    }
}

/**
 * Safe factory for a fenced/stale lease failure.
 *
 * Distinct from [safePersistenceFailure] (which maps LEASE/CONFLICT to
 * [WorkflowLeaseConflictException]): the worker relies on
 * [StaleWorkflowLeaseException] as a semantic class for lease-fencing
 * failures, so the boundary must preserve that class with fixed text.
 */
internal fun safeStaleWorkflowLeaseFailure(): StaleWorkflowLeaseException =
    StaleWorkflowLeaseException("Workflow lease is no longer active")
        .also { it.setPersistenceFailureMetadata(PersistenceFailureCode.CONFLICT) }

internal suspend fun <T> persistenceBoundary(
    resourceKind: PersistenceResourceKind,
    operation: PersistenceOperation,
    diagnosticObserver: PersistenceFailureDiagnosticObserver,
    classify: (Throwable) -> PersistenceFailureCode? = { null },
    block: suspend () -> T,
): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    error.rethrowIfCancellation()
    if (error.persistenceFailureTrusted()) throw error

    val code = classify(error) ?: error.persistenceFailureCode() ?: defaultPersistenceFailureCode(operation, error)
    deliverPersistenceFailure(
        diagnosticObserver,
        PersistenceFailureDiagnosticEvent(resourceKind, operation, code, error),
    )
    // Untrusted caller-constructed StaleWorkflowLeaseException values keep their
    // semantic class (the worker relies on it) but lose any raw text.
    if (error is StaleWorkflowLeaseException) throw safeStaleWorkflowLeaseFailure()
    throw safePersistenceFailure(resourceKind, operation, code)
}

internal fun checkpointDiagnosticObserver(store: WorkflowCheckpointStore): PersistenceFailureDiagnosticObserver = when (store) {
    is FileWorkflowCheckpointStore -> store.persistenceFailureDiagnosticObserver
    is MarkdownWorkflowCheckpointStore -> store.persistenceFailureDiagnosticObserver
    is JdbcWorkflowCheckpointStore -> store.persistenceFailureDiagnosticObserver
    is InMemoryWorkflowCheckpointStore -> store.persistenceFailureDiagnosticObserver
    else -> NoOpPersistenceFailureDiagnosticObserver
}

private fun Throwable.persistenceFailureTrusted(): Boolean = when (this) {
    is WorkflowResumeException -> safeFactoryTrusted
    is WorkflowCheckpointConflictException -> safeFactoryTrusted
    is WorkflowCheckpointCorruptionException -> safeFactoryTrusted
    is WorkflowLeaseConflictException -> safeFactoryTrusted
    is StaleWorkflowLeaseException -> safeFactoryTrusted
    is StepAttemptRecordCorruptionException -> safeFactoryTrusted
    is WorkflowPersistenceFailureException -> safeFactoryTrusted
    else -> false
}

private fun Throwable.persistenceFailureCode(): PersistenceFailureCode? = when (this) {
    is WorkflowCheckpointConflictException, is WorkflowLeaseConflictException, is StaleWorkflowLeaseException ->
        PersistenceFailureCode.CONFLICT
    is WorkflowCheckpointCorruptionException, is StepAttemptRecordCorruptionException -> PersistenceFailureCode.CORRUPTED_DATA
    is WorkflowResumeException -> failureCode
    is WorkflowPersistenceFailureException -> failureCode
    else -> null
}

private fun defaultPersistenceFailureCode(
    operation: PersistenceOperation,
    error: Throwable,
): PersistenceFailureCode = when {
    error::class.java.simpleName.contains("Conflict", ignoreCase = true) -> PersistenceFailureCode.CONFLICT
    operation == PersistenceOperation.LOAD -> PersistenceFailureCode.READ_FAILED
    operation == PersistenceOperation.LIST -> PersistenceFailureCode.LIST_FAILED
    operation == PersistenceOperation.DELETE -> PersistenceFailureCode.DELETE_FAILED
    operation in setOf(PersistenceOperation.SAVE, PersistenceOperation.COMPARE_AND_SET) -> PersistenceFailureCode.WRITE_FAILED
    else -> PersistenceFailureCode.READ_FAILED
}

private suspend fun deliverPersistenceFailure(
    observer: PersistenceFailureDiagnosticObserver,
    event: PersistenceFailureDiagnosticEvent,
) {
    try {
        observer.onFailure(event)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    } catch (e: Throwable) {
        e.rethrowIfCancellation()
    }
    currentCoroutineContext().ensureActive()
}
