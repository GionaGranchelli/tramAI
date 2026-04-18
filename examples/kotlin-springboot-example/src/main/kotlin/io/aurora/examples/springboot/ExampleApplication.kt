package io.aurora.examples.springboot

import io.aurora.core.annotations.AiDescription
import io.aurora.core.annotations.AiRange
import io.aurora.core.annotations.AiService
import io.aurora.core.annotations.AiTool
import io.aurora.core.annotations.Operation
import io.aurora.core.exception.ProviderException
import io.aurora.core.exception.StructuredOutputException
import io.aurora.core.model.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Minimal Spring Boot application used to verify Aurora integration from Maven Local.
 */
@SpringBootApplication
class ExampleApplication

/**
 * Aurora service contract that exposes both raw and structured operations over the same input.
 *
 * The structured method intentionally uses a smaller schema than before so a local model can satisfy
 * it more consistently while still showing Aurora's typed execution model.
 */
@AiService
interface InvoiceAnalyzer {
    /**
     * Produces a short natural-language summary of the invoice situation.
     */
    @Operation(
        prompt = "Read the invoice-like text and summarize the payment situation in one short sentence.",
        model = "gemma4:e4b",
        providerRetries = 0,
        timeoutMillis = 60_000,
    )
    suspend fun summarize(invoiceText: String): String

    /**
     * Streams a short natural-language summary.
     */
    @Operation(
        prompt = "Read the invoice-like text and summarize the payment situation in one short sentence.",
        model = "deepseek-r1:8b-64k",
        providerRetries = 0,
        timeoutMillis = 60_000,
    )
    suspend fun streamSummarize(invoiceText: String): kotlinx.coroutines.flow.Flow<io.aurora.core.model.StreamChunk>

    /**
     * Enriches invoice data using external tools.
     */
    @Operation(
        prompt = "Identify the vendor and look up their details. Then provide a summary of the vendor's reliability and standard terms.",
        model = "deepseek-r1:8b-64k",
        tools = ["vendor_lookup"],
        timeoutMillis = 60_000,
    )
    suspend fun enrich(invoiceText: String): String

    /**
     * Extracts a typed triage object that can be consumed directly by application code.
     */
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

/**
 * Minimal HTTP controller that exposes both the raw and typed Aurora paths.
 */
@RestController
class InvoiceController(
    private val analyzer: InvoiceAnalyzer,
) {
    /**
     * Small health-style endpoint to confirm that the example is running.
     */
    @GetMapping("/")
    fun home(): Map<String, String> = mapOf(
        "application" to "kotlin-springboot-example",
        "status" to "ok",
        "rawEndpoint" to "POST /invoice/summary",
        "streamEndpoint" to "POST /invoice/summary/stream",
        "enrichEndpoint" to "POST /invoice/enrich",
        "typedEndpoint" to "POST /invoice/triage",
    )

    /**
     * Returns a free-form summary so the typed endpoint has a direct baseline for comparison.
     */
    @PostMapping("/invoice/summary")
    suspend fun summarize(
        @RequestBody request: InvoiceInput,
    ): Map<String, String> = mapOf(
        "summary" to analyzer.summarize(request.invoiceText),
    )

    /**
     * Streams a summary using Server-Sent Events.
     */
    @PostMapping("/invoice/summary/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun streamSummarize(
        @RequestBody request: InvoiceInput,
    ): Flow<String> = analyzer.streamSummarize(request.invoiceText)
        .mapNotNull { chunk ->
            when (chunk) {
                is StreamChunk.Token -> chunk.text
                else -> null
            }
        }

    /**
     * Enriches invoice data using external tools.
     */
    @PostMapping("/invoice/enrich")
    suspend fun enrich(
        @RequestBody request: InvoiceInput,
    ): Map<String, String> = mapOf(
        "enrichment" to analyzer.enrich(request.invoiceText),
    )

    /**
     * Returns a typed object inferred from messy invoice text.
     */
    @PostMapping("/invoice/triage")
    suspend fun triage(
        @RequestBody request: InvoiceInput,
    ): InvoiceTriageResult = analyzer.triage(request.invoiceText).toResponse()
}

/**
 * Small error mapper so the example shows structured Aurora failures cleanly instead of with a stack dump.
 */
@RestControllerAdvice
class AuroraExampleErrorHandler {
    private val logger = LoggerFactory.getLogger(AuroraExampleErrorHandler::class.java)

    /**
     * Converts structured-output failures into a readable JSON error payload.
     */
    @ExceptionHandler(StructuredOutputException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun handleStructuredOutputFailure(error: StructuredOutputException): Map<String, Any?> {
        logger.warn(
            "Aurora structured output failed: validationError='{}', attempts={}, rawResponse='{}'",
            error.validationError,
            error.attemptCount,
            error.lastRawResponse?.replace("\n", "\\n")?.take(800),
        )

        return linkedMapOf(
            "error" to "structured_output_failed",
            "message" to (error.message ?: "Aurora could not produce valid structured output"),
            "validationError" to error.validationError,
            "attemptCount" to error.attemptCount,
            "lastRawResponse" to error.lastRawResponse,
        )
    }

    /**
     * Converts provider failures into a readable JSON error payload.
     */
    @ExceptionHandler(ProviderException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun handleProviderFailure(error: ProviderException): Map<String, Any?> {
        logger.warn(
            "Aurora provider failed: statusCode={}, retryable={}, message='{}'",
            error.statusCode,
            error.retryable,
            error.message,
        )

        return linkedMapOf(
            "error" to "provider_failed",
            "message" to (error.message ?: "Aurora provider call failed"),
            "statusCode" to error.statusCode,
            "retryable" to error.retryable,
        )
    }
}

/**
 * Request body sent to the example endpoints.
 */
data class InvoiceInput(
    val invoiceText: String,
)

/**
 * Internal Aurora result returned by the model-facing structured contract.
 *
 * The selected model naturally emits enum-like values as small objects with `name` and `ordinal`,
 * so the example accepts that shape internally and maps it to cleaner API enums afterwards.
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

/**
 * Nested extracted facts to show that Aurora can produce structured object graphs, not just flat DTOs.
 */
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

/**
 * Internal model token for invoice status.
 */
data class StatusToken(
    @property:AiDescription("Exact enum literal name: CURRENT, DUE_SOON, OVERDUE, DISPUTED, BLOCKED, or UNKNOWN")
    val name: String,
    @property:AiDescription("Optional ordinal value if the model chooses to emit one")
    val ordinal: Int? = null,
)

/**
 * Internal model token for priority.
 */
data class PriorityToken(
    @property:AiDescription("Exact enum literal name: LOW, MEDIUM, HIGH, or CRITICAL")
    val name: String,
    @property:AiDescription("Optional ordinal value if the model chooses to emit one")
    val ordinal: Int? = null,
)

/**
 * Internal model token for the primary action.
 */
data class ActionToken(
    @property:AiDescription("Exact enum literal name: PAY, INVESTIGATE, CONTACT_VENDOR, REQUEST_APPROVAL, ESCALATE, or HOLD")
    val name: String,
    @property:AiDescription("Optional ordinal value if the model chooses to emit one")
    val ordinal: Int? = null,
)

/**
 * Public typed result returned by the HTTP endpoint.
 */
data class InvoiceTriageResult(
    val summary: String,
    val status: InvoiceStatus,
    val priority: TriagePriority,
    val needsImmediateAttention: Boolean,
    val riskScore: Int,
    val facts: ExtractedInvoiceFacts,
    val nextStep: ActionType,
)

/**
 * High-level invoice status classification.
 */
enum class InvoiceStatus {
    CURRENT,
    DUE_SOON,
    OVERDUE,
    DISPUTED,
    BLOCKED,
    UNKNOWN,
}

/**
 * Operational urgency of the invoice.
 */
enum class TriagePriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

/**
 * Recommended primary action returned by the model.
 */
enum class ActionType {
    PAY,
    INVESTIGATE,
    CONTACT_VENDOR,
    REQUEST_APPROVAL,
    ESCALATE,
    HOLD,
}

/**
 * Maps the model-facing structured result into the cleaner public HTTP response type.
 */
fun RawInvoiceTriageResult.toResponse(): InvoiceTriageResult = InvoiceTriageResult(
    summary = summary,
    status = InvoiceStatus.valueOf(status.name),
    priority = TriagePriority.valueOf(priority.name),
    needsImmediateAttention = needsImmediateAttention,
    riskScore = riskScore,
    facts = facts,
    nextStep = ActionType.valueOf(nextStep.name),
)

/**
 * Application entry point.
 */
fun main(args: Array<String>) {
    runApplication<ExampleApplication>(*args)
}

/**
 * External tools discovered by Aurora's Spring adapter.
 */
@Component
class VendorTools {
    @AiTool(
        name = "vendor_lookup",
        description = "Looks up details for a vendor by name, including reliability and terms.",
    )
    fun lookupVendor(input: VendorLookupInput): VendorDetails {
        // In a real app, this would call a database or CRM.
        return when (input.vendorName.lowercase()) {
            "acme" -> VendorDetails("Acme Corp", 4.8, "NET-30")
            "globex" -> VendorDetails("Globex", 3.2, "NET-15")
            else -> VendorDetails(input.vendorName, 4.0, "NET-30 (Standard)")
        }
    }
}

data class VendorLookupInput(
    @property:AiDescription("The name of the vendor to look up")
    val vendorName: String,
)

data class VendorDetails(
    val fullName: String,
    val reliabilityScore: Double,
    val paymentTerms: String,
)
