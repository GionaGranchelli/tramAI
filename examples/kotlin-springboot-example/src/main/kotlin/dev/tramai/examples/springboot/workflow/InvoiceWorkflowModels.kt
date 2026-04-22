package dev.tramai.examples.springboot.workflow

import dev.tramai.examples.springboot.api.InvoiceTriageResponse

data class InvoiceWorkflowRequest(
    val invoiceText: String,
    val workflowId: String? = null,
)

data class InvoiceWorkflowStartResponse(
    val workflowId: String,
    val status: WorkflowExecutionStatus,
    val acceptedAtEpochMillis: Long,
)

data class InvoiceWorkflowExecution(
    val workflowId: String,
    val result: InvoiceWorkflowResult,
)

data class InvoiceWorkflowRunView(
    val workflowId: String,
    val status: WorkflowExecutionStatus,
    val result: InvoiceWorkflowResult?,
    val errorMessage: String?,
    val checkpoint: InvoiceWorkflowCheckpointView?,
)

data class InvoiceWorkflowRunSummary(
    val workflowId: String,
    val status: WorkflowExecutionStatus,
    val updatedAtEpochMillis: Long,
    val hasResult: Boolean,
    val errorMessage: String?,
)

data class InvoiceWorkflowEventView(
    val timestampEpochMillis: Long,
    val type: WorkflowEventType,
    val status: WorkflowExecutionStatus?,
    val message: String,
)

data class InvoiceWorkflowCancelResponse(
    val workflowId: String,
    val status: WorkflowExecutionStatus,
    val cancelledAtEpochMillis: Long,
)

data class InvoiceWorkflowResult(
    val summary: String,
    val triage: InvoiceTriageResponse,
    val enrichment: String?,
    val handlingLane: WorkflowLane,
    val operatorBrief: String,
)

data class InvoiceWorkflowCheckpointView(
    val workflowName: String,
    val workflowId: String,
    val nextStepIndex: Int,
    val stepExecutions: Int,
    val lastCompletedStepName: String?,
    val revision: Long,
    val savedAtEpochMillis: Long,
    val metadata: Map<String, String>,
)

enum class WorkflowLane {
    ESCALATION,
    STANDARD_REVIEW,
}

enum class WorkflowExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class WorkflowEventType {
    ACCEPTED,
    RUN_STARTED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED,
    CANCEL_REQUESTED,
    RESUME_REQUESTED,
}

data class InvoiceWorkflowState(
    val invoiceText: String,
    val summary: String? = null,
    val triage: InvoiceTriageResponse? = null,
    val enrichment: String? = null,
    val handlingLane: WorkflowLane? = null,
    val operatorBrief: String? = null,
)

class WorkflowAlreadyRunningException(
    message: String,
) : RuntimeException(message)

class WorkflowNotRunningException(
    message: String,
) : RuntimeException(message)

class WorkflowNotFoundException(
    message: String,
) : RuntimeException(message)
