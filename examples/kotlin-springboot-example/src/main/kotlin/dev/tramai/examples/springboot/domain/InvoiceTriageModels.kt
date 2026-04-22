package dev.tramai.examples.springboot.domain

import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiRange
import dev.tramai.examples.springboot.api.InvoiceTriageResponse

/**
 * Model-facing structured contract.
 *
 * The local model used by the example is more reliable when enum-like values are emitted
 * as small objects, so the example keeps that internal wire shape and maps it to a cleaner API DTO.
 */
data class RawInvoiceTriageResult(
    @property:AiDescription("Short human-readable summary of the invoice situation")
    val summary: String,
    @property:AiDescription("Detected payment state or issue category represented as an object with name and ordinal")
    val status: StatusToken,
    @property:AiDescription("Operational urgency for the invoice triage represented as an object with name and ordinal")
    val priority: PriorityToken,
    @property:AiDescription("Whether the invoice should be looked at immediately by an operator")
    val needsImmediateAttention: Boolean,
    @property:AiDescription("Simple risk score from 1 to 5 where 5 is highest risk")
    @property:AiRange(min = 1.0, max = 5.0)
    val riskScore: Int,
    @property:AiDescription("Important entities or facts extracted from the text")
    val facts: ExtractedInvoiceFacts,
    @property:AiDescription("Primary next step the operator should take represented as an object with name and ordinal")
    val nextStep: ActionToken,
)

data class ExtractedInvoiceFacts(
    @property:AiDescription("Invoice identifier if one is present in the text")
    val invoiceId: String?,
    @property:AiDescription("Vendor or supplier name if present")
    val vendor: String?,
    @property:AiDescription("Amount due exactly as seen in the text when present")
    val amountDueText: String?,
    @property:AiDescription("Due date exactly as seen in the text when present")
    val dueDate: String?,
)

data class StatusToken(
    @property:AiDescription("Exact enum literal name: CURRENT, DUE_SOON, OVERDUE, DISPUTED, BLOCKED, or UNKNOWN")
    val name: String,
    @property:AiDescription("Optional ordinal value if the model chooses to emit one")
    val ordinal: Int? = null,
)

data class PriorityToken(
    @property:AiDescription("Exact enum literal name: LOW, MEDIUM, HIGH, or CRITICAL")
    val name: String,
    @property:AiDescription("Optional ordinal value if the model chooses to emit one")
    val ordinal: Int? = null,
)

data class ActionToken(
    @property:AiDescription("Exact enum literal name: PAY, INVESTIGATE, CONTACT_VENDOR, REQUEST_APPROVAL, ESCALATE, or HOLD")
    val name: String,
    @property:AiDescription("Optional ordinal value if the model chooses to emit one")
    val ordinal: Int? = null,
)

enum class InvoiceStatus {
    CURRENT,
    DUE_SOON,
    OVERDUE,
    DISPUTED,
    BLOCKED,
    UNKNOWN,
}

enum class TriagePriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class ActionType {
    PAY,
    INVESTIGATE,
    CONTACT_VENDOR,
    REQUEST_APPROVAL,
    ESCALATE,
    HOLD,
}

fun RawInvoiceTriageResult.toApiResponse(): InvoiceTriageResponse = InvoiceTriageResponse(
    summary = summary,
    status = InvoiceStatus.valueOf(status.name),
    priority = TriagePriority.valueOf(priority.name),
    needsImmediateAttention = needsImmediateAttention,
    riskScore = riskScore,
    facts = facts,
    nextStep = ActionType.valueOf(nextStep.name),
)
