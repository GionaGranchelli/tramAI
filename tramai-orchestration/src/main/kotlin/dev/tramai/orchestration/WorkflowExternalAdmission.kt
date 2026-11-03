package dev.tramai.orchestration

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The effect kind E8 grants are taken for. The durable record carries the plugin step type as well, because "EXTERNAL"
 * alone would not say which executor was authorized.
 */
internal const val EXTERNAL_EFFECT_KIND: String = "EXTERNAL"

/** The effect kind recorded for one external executor invocation (E8): `EXTERNAL:<typeId>`. */
internal fun externalEffectKind(typeId: String): String = "$EXTERNAL_EFFECT_KIND:$typeId"

/**
 * External executor admission (E8) as a capability distinct from step, process, HTTP and MCP admission.
 *
 * What a permit here establishes is narrow and must be described that way: **this call into the executor was
 * admitted**. It cannot observe, count or fence the subprocesses, HTTP requests or other outbound work the executor
 * performs inside its own code -- `ExternalStepExecutor` is a third-party extension point, so §3a applies. A step
 * admission does not authorize it, and it authorizes nothing downstream.
 *
 * The type id is part of the grant rather than a constant kind: two plugin steps of different types are different
 * effects, and the durable record has to say which one was authorized.
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
