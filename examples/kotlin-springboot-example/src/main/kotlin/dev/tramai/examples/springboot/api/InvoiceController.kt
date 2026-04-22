package dev.tramai.examples.springboot.api

import dev.tramai.examples.springboot.application.InvoiceExampleFacade
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowCancelResponse
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowCheckpointView
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowCoordinator
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowEventView
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowExecution
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowRequest
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowRunSummary
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowRunView
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowStartResponse
import dev.tramai.examples.springboot.workflow.WorkflowNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP adapter only.
 *
 * Tramai behavior lives behind the facade and workflow coordinator so the controller
 * stays focused on request and response mapping.
 */
@RestController
class InvoiceController(
    private val facade: InvoiceExampleFacade,
    private val workflowCoordinator: InvoiceWorkflowCoordinator,
) {
    @PostMapping("/invoice/summary")
    suspend fun summarize(
        @RequestBody request: InvoiceInput,
    ): InvoiceSummaryResponse = facade.summarize(request.invoiceText)

    @PostMapping("/invoice/summary/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamSummarize(
        @RequestBody request: InvoiceInput,
    ) = facade.streamSummary(request.invoiceText)

    @PostMapping("/invoice/enrich")
    suspend fun enrich(
        @RequestBody request: InvoiceInput,
    ): Map<String, String> = facade.enrich(request.invoiceText)

    @PostMapping("/invoice/triage")
    suspend fun triage(
        @RequestBody request: InvoiceInput,
    ): InvoiceTriageResponse = facade.triage(request.invoiceText)

    @PostMapping("/invoice/workflow")
    suspend fun workflow(
        @RequestBody request: InvoiceWorkflowRequest,
    ): InvoiceWorkflowExecution = workflowCoordinator.run(request)

    @PostMapping("/invoice/workflow/start")
    fun startWorkflow(
        @RequestBody request: InvoiceWorkflowRequest,
    ): ResponseEntity<InvoiceWorkflowStartResponse> = ResponseEntity
        .status(HttpStatus.ACCEPTED)
        .body(workflowCoordinator.start(request))

    @GetMapping("/invoice/workflow/result/{workflowId}")
    suspend fun workflowResult(
        @PathVariable workflowId: String,
    ): InvoiceWorkflowRunView = workflowCoordinator.loadRun(workflowId)
        ?: throw WorkflowNotFoundException(
            "No workflow exists for workflowId '$workflowId'",
        )

    @GetMapping("/invoice/workflow/list")
    suspend fun workflowList(
        @RequestParam(name = "limit", required = false, defaultValue = "20") limit: Int,
    ): List<InvoiceWorkflowRunSummary> = workflowCoordinator.listRuns(limit)

    @PostMapping("/invoice/workflow/cancel/{workflowId}")
    suspend fun workflowCancel(
        @PathVariable workflowId: String,
    ): ResponseEntity<InvoiceWorkflowCancelResponse> = ResponseEntity
        .status(HttpStatus.ACCEPTED)
        .body(workflowCoordinator.cancel(workflowId))

    @GetMapping("/invoice/workflow/checkpoint/{workflowId}")
    suspend fun workflowCheckpoint(
        @PathVariable workflowId: String,
    ): InvoiceWorkflowCheckpointView = workflowCoordinator.loadCheckpoint(workflowId)
        ?: throw WorkflowNotFoundException(
            "No checkpoint exists for workflowId '$workflowId'",
        )

    @PostMapping("/invoice/workflow/resume/{workflowId}")
    suspend fun resumeWorkflow(
        @PathVariable workflowId: String,
    ): InvoiceWorkflowExecution = workflowCoordinator.resume(workflowId)

    @GetMapping("/invoice/workflow/events/{workflowId}")
    suspend fun workflowEvents(
        @PathVariable workflowId: String,
    ): List<InvoiceWorkflowEventView> = workflowCoordinator.loadEvents(workflowId)
        ?: throw WorkflowNotFoundException(
            "No workflow exists for workflowId '$workflowId'",
        )
}
