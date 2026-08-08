package dev.tramai.orchestration

fun interface WorkflowStepFailureDiagnosticObserver { suspend fun onFailure(event: WorkflowStepFailureDiagnosticEvent) }

data class WorkflowStepFailureDiagnosticEvent(
    val workflowName: String, val stepName: String, val kind: WorkflowStepKind,
    val code: WorkflowStepFailureCode, val attempt: Int, val willRetryOrReconnect: Boolean,
    val failure: Throwable?, val detailPreview: String?, val detailTruncated: Boolean,
    val numericMetadata: Map<String, Long> = emptyMap(),
)

object NoOpWorkflowStepFailureDiagnosticObserver : WorkflowStepFailureDiagnosticObserver {
    override suspend fun onFailure(event: WorkflowStepFailureDiagnosticEvent) = Unit
}
