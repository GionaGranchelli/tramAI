@file:OptIn(dev.tramai.orchestration.ExperimentalTramAIOrchestration::class)

package dev.tramai.examples.springboot

import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiRange
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.AiTool
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.core.model.StreamChunk
import kotlinx.coroutines.flow.mapNotNull
import dev.tramai.orchestration.WorkflowCheckpointConflictException
import dev.tramai.orchestration.WorkflowLeaseConflictException
import dev.tramai.orchestration.WorkflowResumeException
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Minimal Spring Boot application used to verify TramAI integration from the current repository checkout.
 */
@SpringBootApplication
class ExampleApplication

/**
 * TramAI service contract that exposes both raw and structured operations over the same input.
 *
 * The structured method intentionally uses a smaller schema than before so a local model can satisfy
 * it more consistently while still showing TramAI's typed execution model.
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
        timeoutMillis = 360_000,
    )
    suspend fun summarize(invoiceText: String): String

    /**
     * Streams a short natural-language summary.
     */
    @Operation(
        prompt = "Read the invoice-like text and summarize the payment situation in one short sentence.",
        model = "deepseek-r1:8b-64k",
        providerRetries = 0,
        timeoutMillis = 360_000,
    )
    fun streamSummarize(invoiceText: String): Flow<StreamChunk>

    /**
     * Enriches invoice data using external tools.
     */
    @Operation(
        prompt = "Identify the vendor and look up their details. Then provide a summary of the vendor's reliability and standard terms.",
        model = "deepseek-r1:8b-64k",
        tools = ["vendor_lookup"],
        timeoutMillis = 360_000,
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
 * Minimal HTTP controller that exposes both the raw and typed TramAI paths.
 */
@RestController
class InvoiceController(
    private val analyzer: InvoiceAnalyzer,
    private val workflowCoordinator: InvoiceWorkflowCoordinator,
) {
    /**
     * Small health-style endpoint to confirm that the example is running.
     */
    @GetMapping("/")
    fun home(): Map<String, String> = mapOf(
        "product" to "TramAI",
        "application" to "kotlin-springboot-example",
        "status" to "ok",
        "rawEndpoint" to "POST /invoice/summary",
        "streamEndpoint" to "POST /invoice/summary/stream",
        "enrichEndpoint" to "POST /invoice/enrich",
        "typedEndpoint" to "POST /invoice/triage",
        "workflowEndpoint" to "POST /invoice/workflow",
        "workflowStartEndpoint" to "POST /invoice/workflow/start",
        "workflowResultEndpoint" to "GET /invoice/workflow/result/{workflowId}",
        "workflowListEndpoint" to "GET /invoice/workflow/list",
        "workflowCancelEndpoint" to "POST /invoice/workflow/cancel/{workflowId}",
        "workflowCheckpointEndpoint" to "GET /invoice/workflow/checkpoint/{workflowId}",
        "workflowResumeEndpoint" to "POST /invoice/workflow/resume/{workflowId}",
        "workflowEventsEndpoint" to "GET /invoice/workflow/events/{workflowId}",
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

    /**
     * Runs a persisted workflow that composes raw, structured, and tool-enabled operations.
     */
    @PostMapping("/invoice/workflow")
    suspend fun workflow(
        @RequestBody request: InvoiceWorkflowRequest,
    ): InvoiceWorkflowExecution = workflowCoordinator.run(request)

    /**
     * Starts a workflow asynchronously and returns immediately.
     */
    @PostMapping("/invoice/workflow/start")
    fun startWorkflow(
        @RequestBody request: InvoiceWorkflowRequest,
    ): ResponseEntity<InvoiceWorkflowStartResponse> = ResponseEntity
        .status(HttpStatus.ACCEPTED)
        .body(workflowCoordinator.start(request))

    /**
     * Returns workflow status and result payload when available.
     */
    @GetMapping("/invoice/workflow/result/{workflowId}")
    suspend fun workflowResult(
        @PathVariable workflowId: String,
    ): InvoiceWorkflowRunView = workflowCoordinator.loadRun(workflowId)
        ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No workflow exists for workflowId '$workflowId'",
        )

    /**
     * Lists recent workflows discovered from active runs and checkpoint storage.
     */
    @GetMapping("/invoice/workflow/list")
    suspend fun workflowList(
        @RequestParam(name = "limit", required = false, defaultValue = "20") limit: Int,
    ): List<InvoiceWorkflowRunSummary> = workflowCoordinator.listRuns(limit)

    /**
     * Cancels an active workflow run.
     */
    @PostMapping("/invoice/workflow/cancel/{workflowId}")
    suspend fun workflowCancel(
        @PathVariable workflowId: String,
    ): ResponseEntity<InvoiceWorkflowCancelResponse> = ResponseEntity
        .status(HttpStatus.ACCEPTED)
        .body(workflowCoordinator.cancel(workflowId))

    /**
     * Loads the current persisted checkpoint for inspection.
     */
    @GetMapping("/invoice/workflow/checkpoint/{workflowId}")
    suspend fun workflowCheckpoint(
        @PathVariable workflowId: String,
    ): InvoiceWorkflowCheckpointView = workflowCoordinator.loadCheckpoint(workflowId)
        ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No checkpoint exists for workflowId '$workflowId'",
        )

    /**
     * Resumes a previously persisted workflow.
     */
    @PostMapping("/invoice/workflow/resume/{workflowId}")
    suspend fun resumeWorkflow(
        @PathVariable workflowId: String,
    ): InvoiceWorkflowExecution = workflowCoordinator.resume(workflowId)

    /**
     * Returns the lifecycle events recorded for a workflow execution.
     */
    @GetMapping("/invoice/workflow/events/{workflowId}")
    suspend fun workflowEvents(
        @PathVariable workflowId: String,
    ): List<InvoiceWorkflowEventView> = workflowCoordinator.loadEvents(workflowId)
        ?: throw WorkflowNotFoundException(
            "No workflow exists for workflowId '$workflowId'",
        )
}

/**
 * Small error mapper so the example shows structured TramAI failures cleanly instead of with a stack dump.
 */
@RestControllerAdvice
class TramAIExampleErrorHandler {
    private val logger = LoggerFactory.getLogger(TramAIExampleErrorHandler::class.java)

    /**
     * Converts structured-output failures into a readable JSON error payload.
     */
    @ExceptionHandler(StructuredOutputException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun handleStructuredOutputFailure(error: StructuredOutputException): Map<String, Any?> {
        logger.warn(
            "TramAI structured output failed: validationError='{}', attempts={}, rawResponse='{}'",
            error.validationError,
            error.attemptCount,
            error.lastRawResponse?.replace("\n", "\\n")?.take(800),
        )

        return linkedMapOf(
            "error" to "structured_output_failed",
            "message" to (error.message ?: "TramAI could not produce valid structured output"),
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
            "TramAI provider failed: statusCode={}, retryable={}, message='{}'",
            error.statusCode,
            error.retryable,
            error.message,
        )

        return linkedMapOf(
            "error" to "provider_failed",
            "message" to (error.message ?: "TramAI provider call failed"),
            "statusCode" to error.statusCode,
            "retryable" to error.retryable,
        )
    }

    /**
     * Converts missing workflow checkpoints into a readable 404 response.
     */
    @ExceptionHandler(WorkflowResumeException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleWorkflowResumeFailure(error: WorkflowResumeException): Map<String, Any?> = linkedMapOf(
        "error" to "workflow_resume_failed",
        "message" to (error.message ?: "Workflow checkpoint was not found"),
    )

    /**
     * Converts persistence and lease ownership conflicts into a readable 409 response.
     */
    @ExceptionHandler(
        WorkflowCheckpointConflictException::class,
        WorkflowLeaseConflictException::class,
    )
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleWorkflowConflict(error: RuntimeException): Map<String, Any?> = linkedMapOf(
        "error" to "workflow_conflict",
        "message" to (error.message ?: "Workflow persistence conflict"),
    )

    /**
     * Converts async start conflicts into a readable 409 response.
     */
    @ExceptionHandler(WorkflowAlreadyRunningException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleWorkflowAlreadyRunning(error: WorkflowAlreadyRunningException): Map<String, Any?> = linkedMapOf(
        "error" to "workflow_already_running",
        "message" to (error.message ?: "Workflow is already running"),
    )

    /**
     * Converts missing workflows into a readable 404 response.
     */
    @ExceptionHandler(WorkflowNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleWorkflowNotFound(error: WorkflowNotFoundException): Map<String, Any?> = linkedMapOf(
        "error" to "workflow_not_found",
        "message" to (error.message ?: "Workflow was not found"),
    )

    /**
     * Converts cancel requests for inactive workflows into a readable 409 response.
     */
    @ExceptionHandler(WorkflowNotRunningException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleWorkflowNotRunning(error: WorkflowNotRunningException): Map<String, Any?> = linkedMapOf(
        "error" to "workflow_not_running",
        "message" to (error.message ?: "Workflow is not running"),
    )
}

/**
 * Request body sent to the example endpoints.
 */
data class InvoiceInput(
    val invoiceText: String,
)

/**
 * Internal TramAI result returned by the model-facing structured contract.
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
 * Nested extracted facts to show that TramAI can produce structured object graphs, not just flat DTOs.
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
 * External tools discovered by TramAI's Spring adapter.
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
