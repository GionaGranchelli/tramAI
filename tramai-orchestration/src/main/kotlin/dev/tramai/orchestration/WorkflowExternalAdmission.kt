package dev.tramai.orchestration

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The effect kind E8 grants are taken for.
 *
 * [externalEffectKind] appends the plugin type so a permit and a refusal say *which* executor was authorized. That
 * attribution is in memory only: [WorkflowAdmissionRequest] carries no effect kind, so the durable record proves
 * a grant was issued and nothing about which effect it was for. Durable per-effect attribution is D7's.
 */
internal const val EXTERNAL_EFFECT_KIND: String = "EXTERNAL"

/** The effect kind recorded for one external executor invocation (E8): `EXTERNAL:<typeId>`. */
internal fun externalEffectKind(typeId: String): String = "$EXTERNAL_EFFECT_KIND:$typeId"

internal const val EXTERNAL_RESOLUTION_FAILED_DIAGNOSTIC = "external_resolution_failed"
internal const val EXTERNAL_EXECUTION_FAILED_DIAGNOSTIC = "external_execution_failed"
internal const val EXTERNAL_CANCELLED_DIAGNOSTIC = "external_cancelled"
internal const val EXTERNAL_EVIDENCE_FAILED_DIAGNOSTIC = "external_evidence_failed"

/**
 * Failure when reading or writing durable effect evidence records for external executor steps.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ExternalEvidencePersistenceException(
    cause: Throwable,
) : RuntimeException("External evidence persistence failed", cause),
    kotlinx.coroutines.CopyableThrowable<ExternalEvidencePersistenceException> {
    override fun createCopy(): ExternalEvidencePersistenceException =
        ExternalEvidencePersistenceException(cause ?: this).also { copy ->
            for (suppressedError in suppressed) {
                copy.addSuppressed(suppressedError)
            }
        }
}

/**
 * External executor admission (E8) as a capability distinct from step, process, HTTP and MCP admission.
 *
 * What a permit here establishes is narrow and must be described that way: **this call into the executor was
 * admitted**. It cannot observe, count or fence the subprocesses, HTTP requests or other outbound work the executor
 * performs inside its own code -- `ExternalStepExecutor` is a third-party extension point, so §3a applies. A step
 * admission does not authorize it, and it authorizes nothing downstream.
 *
 * The type id belongs to the permit's attribution rather than to a constant kind, because two plugin steps of
 * different types are two different effects and a refusal has to name which one. The durable admission record stores
 * only the authorization sequence, never the effect kind: per-effect durable attribution remains part of D7.
 */
internal fun interface WorkflowExternalAdmission {
    suspend fun admitExternalStep(
        stepName: String,
        typeId: String,
    ): WorkflowExternalPermit
}

/** A durable grant for one external executor invocation (E8). */
internal class WorkflowExternalPermit(
    stepName: String,
    effectKind: String,
    admissionSequence: Long,
    val checkpointGeneration: String = "",
) : EffectPermit(stepName, effectKind, admissionSequence) {
    override val effectLabel: String get() = "external executor"
}

/**
 * The external-executor authority in force for the current coroutine frame, established by [WorkflowRunner] next to the
 * process, HTTP and MCP scopes so a nested plugin step inherits it rather than receiving a new one.
 */
internal class WorkflowExternalAdmissionScope(
    val capability: WorkflowExternalAdmission?,
) : AbstractCoroutineContextElement(WorkflowExternalAdmissionScope) {
    companion object Key : CoroutineContext.Key<WorkflowExternalAdmissionScope>
}

/** The external-executor authority for this frame, or null when no governed execution established one. */
internal suspend fun currentExternalAdmission(): WorkflowExternalAdmission? {
    val scope = currentCoroutineContext()[WorkflowExternalAdmissionScope]
    return scope?.capability
}
