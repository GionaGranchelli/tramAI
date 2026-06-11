package dev.tramai.examples.sovereign

import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.model.ClassifiedDocument

/**
 * A raw invoice document submitted for analysis.
 */
data class InvoiceDocument(
    val invoiceId: String,
    val supplierName: String,
    val iban: String,
    val amountCents: Long,
    val currency: String,
    val description: String,
)

/**
 * Risk classification for an assessed invoice.
 */
enum class InvoiceRisk {
    LOW,
    HIGH,
}

/**
 * Action recommended for an assessed invoice.
 */
enum class InvoiceAction {
    REVIEW_ONLY,
    SCHEDULE_PAYMENT,
}

/**
 * Result of the sovereign document intelligence assessment.
 */
data class InvoiceAssessment(
    val invoiceId: String,
    val supplierName: String,
    val amountCents: Long,
    val currency: String,
    val risk: InvoiceRisk,
    val recommendedAction: InvoiceAction,
    val rationale: String,
)

/**
 * Input for the schedule-payment tool.
 */
data class SchedulePaymentInput(
    val invoiceId: String,
    val iban: String,
    val amountCents: Long,
    val currency: String,
)

/**
 * Result of the schedule-payment tool.
 */
data class SchedulePaymentResult(
    val paymentReference: String,
    val status: String,
)

/**
 * Helper to create a RESTRICTED classified invoice document.
 */
fun classifiedInvoice(
    invoiceId: String = "INV-001",
    supplierName: String = "Acme Corp",
    iban: String = "DE89370400440532013000",
    amountCents: Long = 150_000_00L,
    currency: String = "EUR",
    description: String = "Enterprise license renewal Q3",
): ClassifiedDocument<InvoiceDocument> = ClassifiedDocument(
    payload = InvoiceDocument(
        invoiceId = invoiceId,
        supplierName = supplierName,
        iban = iban,
        amountCents = amountCents,
        currency = currency,
        description = description,
    ),
    classification = DataClassification.RESTRICTED,
    source = ClassificationSource.DECLARED,
)
