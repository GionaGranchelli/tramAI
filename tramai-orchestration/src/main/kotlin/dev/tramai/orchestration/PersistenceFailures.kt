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

/**
 * Public, diagnostic-only view of a corrupt persisted payload.
 *
 * An external [PersistenceFailureDiagnosticObserver] receives the internal
 * corrupt carrier in [PersistenceFailureDiagnosticEvent.failure] (directly for
 * normal corruption events; as the cause chain of an internal marker such as
 * [CheckpointDmlFailure] for fenced JDBC DML). Casting the reachable carrier to
 * this interface is the only public way to read the raw payload that failed to
 * decode. The payload is never forwarded to caller-visible exceptions, logs,
 * audit, or telemetry.
 */
interface PersistenceCorruptionDetail {
    /** Raw persisted payload that failed to decode (diagnostic-only). */
    val corruptPayload: String?
}

object NoOpPersistenceFailureDiagnosticObserver : PersistenceFailureDiagnosticObserver {
    override suspend fun onFailure(event: PersistenceFailureDiagnosticEvent) = Unit
}

internal class CorruptCheckpointException(
    message: String,
    val rawPayload: String?,
) : RuntimeException(message), PersistenceCorruptionDetail {
    override val corruptPayload: String? get() = rawPayload
}

/**
 * Marker raised inside [executeJdbcCancellable]'s non-suspend block when the
 * checkpoint DML phase of a fenced operation fails. The outer lease boundary
 * recognizes it and routes the raw failure to the checkpoint store's diagnostic
 * observer with resourceKind=CHECKPOINT instead of misattributing it to LEASE.
 */
internal class CheckpointDmlFailure(
    val raw: Throwable,
) : RuntimeException("Checkpoint DML failed", raw)

/**
 * Marker raised when a lease read phase fails inside a compound operation
 * (CLAIM/RENEW/RELEASE perform reads before/during their mutation). The
 * low-level failing phase chooses the failure code: the boundary classifies
 * this as READ_FAILED while the outer operation (CLAIM/RENEW/RELEASE) supplies
 * the operation context.
 */
internal class LeaseReadPhaseFailure(
    val raw: Throwable,
) : RuntimeException("Lease read phase failed", raw)

/**
 * Marker raised when a lease DELETE phase fails inside an operation whose
 * default code would be wrong (e.g. expired-lease cleanup during LOAD/RENEW).
 * The failing phase is DELETE_FAILED; the outer operation context is preserved.
 */
internal class LeaseDeletePhaseFailure(
    val raw: Throwable,
) : RuntimeException("Lease delete phase failed", raw)

internal class CorruptStepAttemptException(
    message: String,
    val rawPayload: String?,
    cause: Throwable? = null,
) : RuntimeException(message, cause), PersistenceCorruptionDetail {
    override val corruptPayload: String? get() = rawPayload
}

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
    // Never pass a throwable identity across an external surface: JDBC cleanup
    // can mutate a trusted exception after construction (addSuppressed). Trust
    // only immutable classification; reconstruct a fresh fixed-text instance.
    if (error.persistenceFailureTrusted()) return reconstructSafePersistenceFailure(error)

    val code = error.persistenceFailureCode() ?: defaultPersistenceFailureCode(operation)
    return safePersistenceFailure(resourceKind, operation, code)
}

/**
 * True when [error] is a persistence-family failure (a trusted safe exception,
 * an untrusted caller-constructed persistence exception, or a raw failure
 * classified as one by the boundary). Used by the worker to decide whether a
 * step-attempt failure is a persistence failure (sanitize) or a user
 * step-execution error (keep the real message).
 */
internal fun Throwable.isPersistenceFamilyFailure(): Boolean =
    persistenceFailureTrusted() || persistenceFailureCode() != null

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
    checkpointDiagnosticObserver: PersistenceFailureDiagnosticObserver? = null,
    block: suspend () -> T,
): T = try {
    block()
} catch (error: CancellationException) {
    // JDBC cancellation/cleanup can attach raw SQLExceptions to the CE graph
    // (Statement.cancel, rollback, auto-commit restore, close). Cancellation
    // stays cancellation, but a caller-visible CE must not carry raw
    // persistence internals. Genuine framework cancellation (a parent
    // JobCancellationException, possibly with framework-only children) passes
    // through untouched. Only a CE carrying a real non-cancellation child
    // (SQLException/IOException from JDBC cleanup) is sanitized: construct a
    // fresh fixed-text CE with a cleanup marker and throw it immediately.
    // NOTE: no diagnostic delivery on this path — deliverPersistenceFailure ends
    // with ensureActive(), which throws under genuine parent cancellation before
    // the sanitized CE could be returned. The docs contract is that genuine
    // cancellation is rethrown and not emitted as an ordinary persistence
    // diagnostic.
    if (error.hasUnsafeCancellationDetail()) {
        throw sanitizePersistenceCancellation(error)
    }
    throw error
} catch (error: Throwable) {
    error.rethrowIfCancellation()
    // Fenced checkpoint DML failures are marked by the lease store so they are
    // attributed to the checkpoint store's observer with resourceKind=CHECKPOINT
    // (the raw DML failure never reaches the lease channel).
    if (error is CheckpointDmlFailure) {
        error.rethrowIfCancellation()
        val checkpointObserver = checkpointDiagnosticObserver
            ?: throw IllegalArgumentException("CheckpointDmlFailure without checkpoint observer", error)
        // Corrupt carriers are internal (no persistenceFailureCode); classify
        // them explicitly so a corrupt read inside fenced DML stays
        // CORRUPTED_DATA -> WorkflowCheckpointCorruptionException, not a generic
        // WRITE/DELETE failure (the P2-2 finding).
        val code = when (error.raw) {
            is CorruptCheckpointException -> PersistenceFailureCode.CORRUPTED_DATA
            else -> error.raw.persistenceFailureCode() ?: defaultPersistenceFailureCode(operation)
        }
        deliverPersistenceFailure(
            checkpointObserver,
            // Deliver the marker graph (raw cause + any cleanup suppressed
            // attached by executeJdbcCancellable) so no diagnostic is lost.
            PersistenceFailureDiagnosticEvent(PersistenceResourceKind.CHECKPOINT, operation, code, error),
        )
        throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, operation, code)
    }
    // A lease read phase inside a compound op (CLAIM/RENEW/RELEASE) failed.
    // The phase picks the code (READ_FAILED); the outer op keeps its context.
    if (error is LeaseReadPhaseFailure) {
        error.rethrowIfCancellation()
        val code = PersistenceFailureCode.READ_FAILED
        deliverPersistenceFailure(
            diagnosticObserver,
            PersistenceFailureDiagnosticEvent(resourceKind, operation, code, error),
        )
        throw safePersistenceFailure(resourceKind, operation, code)
    }
    // A lease DELETE phase (expired-lease cleanup) failed inside an operation
    // whose default would be READ/WRITE_FAILED. The phase picks DELETE_FAILED.
    if (error is LeaseDeletePhaseFailure) {
        error.rethrowIfCancellation()
        val code = PersistenceFailureCode.DELETE_FAILED
        deliverPersistenceFailure(
            diagnosticObserver,
            PersistenceFailureDiagnosticEvent(resourceKind, operation, code, error),
        )
        throw safePersistenceFailure(resourceKind, operation, code)
    }
    if (error.persistenceFailureTrusted()) {
        // Never pass the same throwable instance across the boundary: JDBC
        // cleanup mutates the primary after construction (addSuppressed).
        // Trust only immutable classification, then reconstruct a fresh
        // fixed-text instance of the same semantic class.
        currentCoroutineContext().ensureActive()
        if (error.cause != null || error.suppressed.isNotEmpty()) {
            deliverPersistenceFailure(
                diagnosticObserver,
                PersistenceFailureDiagnosticEvent(
                    resourceKind,
                    operation,
                    error.persistenceFailureCode() ?: defaultPersistenceFailureCode(operation),
                    error,
                ),
            )
        }
        throw reconstructSafePersistenceFailure(error)
    }

    val code = classify(error) ?: error.persistenceFailureCode()
        // Internal corrupt carriers carry no persistenceFailureCode; the store's
        // classify lambda usually maps them, but the boundary must not depend on
        // every call site passing one (the P2-4 finding).
        ?: when (error) {
            is CorruptCheckpointException, is CorruptStepAttemptException -> PersistenceFailureCode.CORRUPTED_DATA
            else -> null
        }
        ?: defaultPersistenceFailureCode(operation)
    deliverPersistenceFailure(
        diagnosticObserver,
        PersistenceFailureDiagnosticEvent(resourceKind, operation, code, error),
    )
    // Untrusted caller-constructed StaleWorkflowLeaseException values keep their
    // semantic class (the worker relies on it) but lose any raw text.
    if (error is StaleWorkflowLeaseException) throw safeStaleWorkflowLeaseFailure()
    throw safePersistenceFailure(resourceKind, operation, code)
}.also {
    // Cancellation can arrive while the block runs and still complete normally
    // (e.g. a deferred released concurrently with the parent cancel). Parent
    // cancellation must win over a normal return — the #223/#224 post-callback
    // rule applied to the boundary itself.
    currentCoroutineContext().ensureActive()
}

/**
 * Fresh, cause-free, suppressed-free instance of the same semantic class as
 * [error], with fixed framework-controlled text. A trusted classification is
 * immutable; the throwable that carried it is not.
 */
private fun reconstructSafePersistenceFailure(error: Throwable): RuntimeException {
    val code = error.persistenceFailureCode() ?: PersistenceFailureCode.READ_FAILED
    val message: String = when (error) {
        is WorkflowResumeException -> "Workflow resume failed"
        is WorkflowCheckpointConflictException -> "Workflow checkpoint conflict"
        is WorkflowLeaseConflictException -> "Workflow lease conflict"
        is StaleWorkflowLeaseException -> "Workflow lease is no longer active"
        is WorkflowCheckpointCorruptionException -> "Persisted workflow checkpoint is invalid"
        is StepAttemptRecordCorruptionException -> "Persisted step-attempt record is invalid"
        is WorkflowPersistenceFailureException -> workflowPersistenceFailureMessage(code)
        else -> "Workflow persistence failed"
    }
    val failure: RuntimeException = when (error) {
        is WorkflowResumeException -> WorkflowResumeException(message)
        is WorkflowCheckpointConflictException -> WorkflowCheckpointConflictException(message)
        is WorkflowLeaseConflictException -> WorkflowLeaseConflictException(message)
        is StaleWorkflowLeaseException -> StaleWorkflowLeaseException(message)
        is WorkflowCheckpointCorruptionException -> WorkflowCheckpointCorruptionException(message)
        is StepAttemptRecordCorruptionException -> StepAttemptRecordCorruptionException(message)
        else -> WorkflowPersistenceFailureException(message)
    }
    failure.setPersistenceFailureMetadata(code)
    return failure
}

/** Fresh CancellationException preserving only fixed text and a cleanup marker. */
private fun sanitizePersistenceCancellation(error: CancellationException): CancellationException {
    // Fixed framework text only: a JDBC driver message can embed SQL or paths.
    val sanitized = CancellationException("Workflow persistence operation cancelled")
    sanitized.addSuppressed(PersistenceCleanupDiagnosticException())
    return sanitized
}

/**
 * True when the throwable graph reachable from [this] (via cause and suppressed,
 * cycle-safe) contains any node that is not a [CancellationException]. A
 * framework JobCancellationException chain is all-CE and passes through
 * untouched; a raw SQLException/IOException nested anywhere in the graph
 * (direct or indirect child) is JDBC cleanup contamination and must be
 * sanitized. Mirrors the recursive leak-inspection semantics of the tests.
 */
private fun Throwable.hasUnsafeCancellationDetail(
): Boolean = hasUnsafeCancellationDetail(ArrayList())

private fun Throwable.hasUnsafeCancellationDetail(
    seen: MutableList<Throwable>,
): Boolean {
    if (seen.any { it === this }) return false
    seen += this
    if (this !is CancellationException) return true
    return (cause?.hasUnsafeCancellationDetail(seen) == true) ||
        suppressed.any { it.hasUnsafeCancellationDetail(seen) }
}

/**
 * Fixed-text marker for persistence-cleanup diagnostics attached to a sanitized
 * CancellationException. Deliberately distinct from the process-cleanup marker
 * so a cancelled JDBC persistence operation never mentions "Process cleanup".
 */
internal class PersistenceCleanupDiagnosticException : RuntimeException(
    "Persistence cleanup had diagnostics",
)

private fun workflowPersistenceFailureMessage(code: PersistenceFailureCode): String = when (code) {
    PersistenceFailureCode.READ_FAILED -> "Workflow persistence read failed"
    PersistenceFailureCode.WRITE_FAILED -> "Workflow persistence write failed"
    PersistenceFailureCode.DELETE_FAILED -> "Workflow persistence delete failed"
    PersistenceFailureCode.LIST_FAILED -> "Workflow persistence list failed"
    PersistenceFailureCode.CONFLICT -> "Workflow persistence conflict"
    PersistenceFailureCode.CORRUPTED_DATA -> "Workflow persistence data is invalid"
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

/**
 * Phase-aware default classification: the high-level operation stays
 * [PersistenceOperation], the failure code names the failing storage phase.
 * Compound operations (CLAIM = read-then-write, RELEASE = delete, etc.) default
 * to their dominant mutating phase; stores override with the [classify] lambda
 * when the actual failing phase differs.
 */
private fun defaultPersistenceFailureCode(operation: PersistenceOperation): PersistenceFailureCode = when (operation) {
    PersistenceOperation.LOAD -> PersistenceFailureCode.READ_FAILED
    PersistenceOperation.LIST -> PersistenceFailureCode.LIST_FAILED
    PersistenceOperation.DELETE, PersistenceOperation.RELEASE -> PersistenceFailureCode.DELETE_FAILED
    PersistenceOperation.SAVE, PersistenceOperation.COMPARE_AND_SET,
    PersistenceOperation.CLAIM, PersistenceOperation.RENEW,
    -> PersistenceFailureCode.WRITE_FAILED
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
