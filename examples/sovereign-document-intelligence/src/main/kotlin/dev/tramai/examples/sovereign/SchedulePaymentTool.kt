package dev.tramai.examples.sovereign

import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import kotlin.reflect.KClass

/**
 * In-memory payment ledger with exactly-once idempotency guarantees.
 *
 * Uses [idempotencyKey] from [ToolExecutionContext] to deduplicate
 * payment scheduling during approval resume flows.
 */
class InMemoryPaymentLedger {
    private val executions = java.util.concurrent.ConcurrentHashMap<String, SchedulePaymentResult>()

    fun scheduleExactlyOnce(
        idempotencyKey: String,
        input: SchedulePaymentInput,
    ): SchedulePaymentResult =
        executions.computeIfAbsent(idempotencyKey) {
            SchedulePaymentResult(
                paymentReference = "payment-${input.invoiceId}",
                status = "SCHEDULED",
            )
        }

    fun executionCount(): Int = executions.size
}

/**
 * HIGH-risk payment tool that requires human approval before execution.
 *
 * The tool carries [ToolSecurityMetadata] with:
 * - permission = "payment.schedule"
 * - risk = HIGH (triggers approval suspension)
 * - approval = HUMAN_REQUIRED
 * - managedNetworkEgress = DENY
 * - audit = FULL
 *
 * Uses the engine-bound [ToolExecutionContext.idempotencyKey] for
 * exactly-once execution guarantees after approval resume.
 */
class SchedulePaymentTool(
    private val ledger: InMemoryPaymentLedger,
) : TramaiTool<SchedulePaymentInput, SchedulePaymentResult> {

    override val name: String = "schedule-payment"

    override val description: String =
        "Schedule a payment for an approved invoice"

    override val inputType: KClass<SchedulePaymentInput> =
        SchedulePaymentInput::class

    override val idempotent: Boolean = true

    override val sideEffectLevel: SideEffectLevel = SideEffectLevel.WRITE

    override val security: ToolSecurityMetadata? = ToolSecurityMetadata(
        permission = "payment.schedule",
        risk = RiskLevel.HIGH,
        approval = ApprovalMode.HUMAN_REQUIRED,
        managedNetworkEgress = ManagedNetworkEgress.DENY,
        audit = AuditDetail.FULL,
    )

    override suspend fun execute(
        input: SchedulePaymentInput,
        context: ToolExecutionContext,
    ): SchedulePaymentResult {
        val idempotencyKey = context.idempotencyKey
            ?: throw IllegalStateException(
                "schedule-payment requires an idempotencyKey from the engine"
            )
        return ledger.scheduleExactlyOnce(idempotencyKey, input)
    }
}
