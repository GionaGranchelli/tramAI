package dev.tramai.examples.toolgovernance

import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.AuditDetail
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only customer lookup tool — LOW risk, AUTO approval.
 *
 * Permission: customer.read
 * Expected outcome at every enforcement point: ALLOW
 */
class CustomerLookupTool : ResolvedTool {
    val callCount = AtomicInteger(0)

    override val name: String = "customer_lookup"
    override val description: String = "Looks up customer information by ID"
    override val inputSchemaJson: String = """{"type":"object","properties":{"customerId":{"type":"string"}}}"""
    override val idempotent: Boolean = true
    override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY
    override val security: ToolSecurityMetadata = ToolSecurityMetadata(
        permission = "customer.read",
        risk = RiskLevel.LOW,
        approval = ApprovalMode.AUTO,
        managedNetworkEgress = ManagedNetworkEgress.ALLOW,
        audit = AuditDetail.DECISION_ONLY,
    )

    override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
        callCount.incrementAndGet()
        return ToolResult.Success("""{"customerId":"CUST-001","name":"John Doe","status":"active"}""")
    }
}

/**
 * Destructive account deletion tool — CRITICAL risk, HUMAN_REQUIRED approval.
 *
 * Permission: account.delete
 * The example policy wrapper denies this at BEFORE_TOOL_EXECUTION regardless of
 * the baseline policy, proving the DENY enforcement point.
 */
class AccountDeleteTool : ResolvedTool {
    val callCount = AtomicInteger(0)

    override val name: String = "account_delete"
    override val description: String = "Permanently deletes a customer account"
    override val inputSchemaJson: String = """{"type":"object","properties":{"accountId":{"type":"string"}}}"""
    override val idempotent: Boolean = false
    override val sideEffectLevel: SideEffectLevel = SideEffectLevel.WRITE
    override val security: ToolSecurityMetadata = ToolSecurityMetadata(
        permission = "account.delete",
        risk = RiskLevel.CRITICAL,
        approval = ApprovalMode.HUMAN_REQUIRED,
        managedNetworkEgress = ManagedNetworkEgress.DENY,
        audit = AuditDetail.FULL,
    )

    override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
        callCount.incrementAndGet()
        return ToolResult.Success("""{"deleted":true,"accountId":"ACC-001"}""")
    }
}

/**
 * Payment execution tool — HIGH risk, HUMAN_REQUIRED approval.
 *
 * Permission: payment.execute
 * The baseline DefaultPolicyEngine grants exposure (customer.read is in allowedPermissions)
 * but the execution policy gate returns REQUIRE_APPROVAL because HIGH risk with
 * HUMAN_REQUIRED approval is above the approval threshold.
 */
class PaymentTool : ResolvedTool {
    val callCount = AtomicInteger(0)

    override val name: String = "payment"
    override val description: String = "Executes a monetary payment"
    override val inputSchemaJson: String = """{"type":"object","properties":{"amount":{"type":"number"},"currency":{"type":"string"}}}"""
    override val idempotent: Boolean = false
    override val sideEffectLevel: SideEffectLevel = SideEffectLevel.WRITE
    override val security: ToolSecurityMetadata = ToolSecurityMetadata(
        permission = "payment.execute",
        risk = RiskLevel.HIGH,
        approval = ApprovalMode.HUMAN_REQUIRED,
        managedNetworkEgress = ManagedNetworkEgress.ALLOWLIST_ONLY,
        audit = AuditDetail.FULL,
    )

    override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
        callCount.incrementAndGet()
        return ToolResult.Success("""{"transactionId":"TXN-001","amount":5000,"currency":"EUR"}""")
    }
}
