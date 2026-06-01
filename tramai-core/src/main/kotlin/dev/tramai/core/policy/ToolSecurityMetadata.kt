package dev.tramai.core.policy

/**
 * Security metadata attached to every [dev.tramai.core.model.TramaiTool].
 *
 * Populated from the tool declaration (programmatic or annotation-driven).
 * The policy engine reads this metadata at [EnforcementPoint.BEFORE_TOOL_EXECUTION].
 *
 * [compatibilityMode] defaults to [CompatibilityMode.STRICT]. Tools carrying
 * [CompatibilityMode.LEGACY_PERMISSIVE] metadata are rejected in secure profiles.
 */
data class ToolSecurityMetadata(
    val permission: String,
    val risk: RiskLevel,
    val approval: ApprovalMode,
    val managedNetworkEgress: ManagedNetworkEgress,
    val audit: AuditDetail,
    val compatibilityMode: CompatibilityMode = CompatibilityMode.STRICT,
) {
    companion object {
        /**
         * Permissive default for existing tools during 0.4.x preview.
         * Rejected in secure profiles — works only when [PolicyMode.LEGACY_PERMISSIVE]
         * is explicitly enabled.
         */
        fun legacyPermissive() = ToolSecurityMetadata(
            permission = "legacy.unrestricted",
            risk = RiskLevel.LOW,
            approval = ApprovalMode.AUTO,
            managedNetworkEgress = ManagedNetworkEgress.ALLOW,
            audit = AuditDetail.MINIMAL,
            compatibilityMode = CompatibilityMode.LEGACY_PERMISSIVE,
        )
    }
}
