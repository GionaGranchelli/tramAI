package dev.tramai.examples.springboot.ai

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.StreamChunk
import dev.tramai.examples.springboot.domain.RawInvoiceTriageResult
import kotlinx.coroutines.flow.Flow

/**
 * Typed Tramai contract used by the example application.
 *
 * The example keeps multiple capabilities on one interface on purpose:
 * it shows that Tramai exposes different execution modes through normal
 * interface methods instead of a chain or agent runtime API.
 */
@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Read the invoice-like text and summarize the payment situation in one short sentence.",
        model = "gemma4:e4b",
        providerRetries = 0,
        timeoutMillis = 360_000,
    )
    suspend fun summarize(invoiceText: String): String

    @Operation(
        prompt = "Read the invoice-like text and summarize the payment situation in one short sentence.",
        model = "deepseek-r1:8b-64k",
        providerRetries = 0,
        timeoutMillis = 360_000,
    )
    fun streamSummarize(invoiceText: String): Flow<StreamChunk>

    @Operation(
        prompt = "Identify the vendor and look up their details. Then provide a summary of the vendor's reliability and standard terms.",
        model = "deepseek-r1:8b-64k",
        tools = ["vendor_lookup"],
        timeoutMillis = 360_000,
    )
    suspend fun enrich(invoiceText: String): String

    @Operation(
        prompt = """
            You are helping an accounts-payable team triage invoices and payment escalation emails.
            Return only one JSON object that matches the requested schema.
            Use null when a fact is not present.
            Keep the summary short and concrete.
            Do not wrap the JSON in markdown.
            Use exactly these top-level keys and no others:
            summary, status, priority, needsImmediateAttention, riskScore, facts, nextStep.
            Use exactly these facts keys and no others:
            invoiceId, vendor, amountDueText, dueDate.
            status must be an object with:
            name = one of CURRENT, DUE_SOON, OVERDUE, DISPUTED, BLOCKED, UNKNOWN
            ordinal = integer or null.
            priority must be an object with:
            name = one of LOW, MEDIUM, HIGH, CRITICAL
            ordinal = integer or null.
            nextStep must be an object with:
            name = one of PAY, INVESTIGATE, CONTACT_VENDOR, REQUEST_APPROVAL, ESCALATE, HOLD
            ordinal = integer or null.
            riskScore must be an integer from 1 to 5.
        """,
        model = "deepseek-r1:8b-64k",
        maxRetries = 2,
        providerRetries = 0,
        timeoutMillis = 360_000,
    )
    suspend fun triage(invoiceText: String): RawInvoiceTriageResult
}
