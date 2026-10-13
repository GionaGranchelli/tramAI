package dev.tramai.examples.springaiboundary

import dev.tramai.security.audit.AuditEmission
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.evidence.RuntimeEvidenceRecord
import dev.tramai.security.evidence.ToolPermissionRuntimeEvidenceExporter
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList

/** Enforcement point reused from the engine's own tool vocabulary, not invented here. */
internal const val BOUNDARY_ENFORCEMENT_POINT = "BEFORE_TOOL_EXECUTION"

/**
 * Records what this boundary decided and whether the operation ran, through the repository's existing
 * audit chain and its existing `tool.permission` evidence exporter.
 *
 * The recorded identity comes from one [BoundaryContext] per wiring. This class alone cannot prevent a
 * caller from building it around a second context; the guarantee is a property of the wiring, and it is
 * held by [GovernedToolCallback], which constructs its recorder from its own context so that no parameter
 * exists through which a foreign identity could arrive.
 *
 * What this evidence does *and does not* mean:
 * - it identifies the enforcement point, the tool, the decision and its reason code;
 * - it carries the governed identity of the run this boundary was wired with, as the reserved
 *   `identity.*` keys plus the canonical `workflowRunId`, so evaluation and evidence cannot drift;
 * - it attests that **this declared boundary** enforced that decision;
 * - it says nothing about any other execution path in the host application.
 *
 * It does **not** independently prove where that identity came from: the reserved keys are carried,
 * not re-derived or cryptographically bound, so the guarantee that they are the governed run's own
 * identity is a property of the wiring (one [BoundaryContext] per boundary), not of this evidence.
 *
 * A governance *availability* failure is recorded as a `DENY` whose reason code is
 * `governance-unavailable`: the operation was refused, and the reason code carries why. That keeps the
 * vocabulary inside the existing tool-permission family rather than inventing a second one.
 */
class BoundaryEvidenceRecorder(
    private val auditEngine: AuditEngine,
    private val context: BoundaryContext,
) {
    // Governed invocations can be concurrent; the ledger is shared state.
    private val events = CopyOnWriteArrayList<AuditEvent>()

    fun recordAllow(toolName: String) = record(toolName, DECISION_ALLOW, reasonCode = null)

    fun recordRefusal(
        toolName: String,
        reasonCode: String,
    ) = record(toolName, DECISION_DENY, reasonCode = reasonCode)

    fun recordRequireApproval(
        toolName: String,
        reasonCode: String,
    ) = record(toolName, DECISION_REQUIRE_APPROVAL, reasonCode = reasonCode)

    /**
     * The evidence this boundary produced, projected through the existing exporter so the shape is the
     * repository's runtime-evidence schema rather than one invented here.
     */
    fun evidence(): List<RuntimeEvidenceRecord> = ToolPermissionRuntimeEvidenceExporter().export(events.toList())

    /** Recorded events, for assertions about count and order. */
    fun events(): List<AuditEvent> = events.toList()

    private fun record(
        toolName: String,
        decision: String,
        reasonCode: String?,
    ) {
        val event =
            runBlocking {
                auditEngine.emit(
                    AuditEmission(
                        auditStreamId = context.runId,
                        workflowRunId = context.runId,
                        correlationId = context.correlationId,
                        actor = context.actorId,
                        enforcementPoint = BOUNDARY_ENFORCEMENT_POINT,
                        decision = decision,
                        policyVersion = context.policyVersion,
                        workflowDigest = null,
                        reasonCode = reasonCode,
                        // Only allowlisted metadata keys, plus the reserved governed-identity keys.
                        // The exporter FILTERS unrecognised keys out and fails closed only for a
                        // missing required toolName (ToolPermissionRuntimeEvidenceExporter: :62
                        // filters, :54 requires). Nothing may rely on an unrecognised key being
                        // rejected. The identity keys are not left to that path: they are read as
                        // attribution, and the exporter refuses a partial tuple rather than
                        // recording it (:74).
                        metadata =
                            mapOf(
                                "toolName" to toolName,
                                "enforcementPoint" to BOUNDARY_ENFORCEMENT_POINT,
                            ) + context.identityAttribution,
                    ),
                )
            }
        events += event
    }

    private companion object {
        const val DECISION_ALLOW = "ALLOW"
        const val DECISION_DENY = "DENY"
        const val DECISION_REQUIRE_APPROVAL = "REQUIRE_APPROVAL"
    }
}
