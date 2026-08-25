package dev.tramai.core.annotations

import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel

/**
 * Marks a method for discovery as a portable tool by framework adapters.
 *
 * Governance metadata is opt-in for backward compatibility. Setting a non-empty
 * [permission] makes the annotation produce strict tool security metadata; secure
 * runtime profiles such as sovereign TramAI require that metadata and reject
 * legacy annotation-driven tools that leave [permission] empty.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class AiTool(
    /** Explicit tool name; defaults to the method name. */
    val name: String = "",
    /** Tool description injected into model tool definitions. */
    val description: String,
    /**
     * Whether repeating execution with the same logical input is safe.
     * This describes repetition/idempotency semantics, not whether TramAI should retry the tool after a failure.
     */
    val idempotent: Boolean = false,
    /** Side-effect classification for the tool. */
    val sideEffectLevel: SideEffectLevel = SideEffectLevel.UNKNOWN,
    /**
     * Permission required to expose and execute this tool.
     *
     * Empty preserves the legacy annotation contract and produces no
     * [dev.tramai.core.policy.ToolSecurityMetadata]. Secure profiles reject
     * tools without security metadata.
     */
    val permission: String = "",
    /** Risk classification used by secure policy profiles when [permission] is set. */
    val risk: RiskLevel = RiskLevel.HIGH,
    /** Approval mode used by secure policy profiles when [permission] is set. */
    val approval: ApprovalMode = ApprovalMode.HUMAN_REQUIRED,
    /**
     * Managed network-egress policy metadata when [permission] is set.
     * This applies only to TramAI-managed egress and does not sandbox arbitrary networking performed inside the method.
     */
    val managedNetworkEgress: ManagedNetworkEgress = ManagedNetworkEgress.DENY,
    /** Audit detail metadata requested for governed execution when [permission] is set. */
    val audit: AuditDetail = AuditDetail.FULL,
)
