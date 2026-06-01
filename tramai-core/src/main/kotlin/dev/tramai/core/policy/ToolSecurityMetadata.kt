package dev.tramai.core.policy

/**
 * Security metadata attached to every [dev.tramai.core.model.TramaiTool].
 *
 * Populated from the tool declaration (programmatic or annotation-driven).
 * The policy engine reads this metadata at [EnforcementPoint.BEFORE_TOOL_EXECUTION].
 */
data class ToolSecurityMetadata(
    val permission: String,
    val risk: RiskLevel,
    val approval: ApprovalMode,
    val networkEgress: NetworkEgress,
    val audit: AuditDetail,
) {
    companion object {
        /** Permissive default used for existing tools in 0.4.x preview. */
        fun legacyPermissive() = ToolSecurityMetadata(
            permission = "legacy.unrestricted",
            risk = RiskLevel.LOW,
            approval = ApprovalMode.AUTO,
            networkEgress = NetworkEgress.ALLOW,
            audit = AuditDetail.MINIMAL,
        )
    }
}
