package dev.tramai.orchestration

const val SECRET_FIXTURE = "sentinel=fixture-sentinel-7f3c;url=https://fixture.invalid/api?q=fixture-sentinel-7f3c;path=/srv/tramai/fixtures/customer-ledger.db;sql=SELECT * FROM invoices WHERE customer_secret='fixture-sentinel-7f3c';arg=--config=fixture-sentinel-7f3c;stderr=fixture stderr: credential rejected;reason=fixture validation reason; mcp=fixture MCP error: tool credential rejected"

fun assertsNoFixture(value: String?): Boolean = value?.contains(SECRET_FIXTURE) != true && value?.contains("fixture-sentinel-7f3c") != true

internal class RecordingDiagnosticObserver : WorkflowStepFailureDiagnosticObserver {
    val events = mutableListOf<WorkflowStepFailureDiagnosticEvent>()

    override suspend fun onFailure(event: WorkflowStepFailureDiagnosticEvent) {
        events += event
    }

    fun single(): WorkflowStepFailureDiagnosticEvent = events.single()
}
