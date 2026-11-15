package dev.tramai.spring.sovereign.ops.rest

import dev.tramai.spring.sovereign.ops.OperatorAuthority
import dev.tramai.spring.sovereign.ops.WorkflowCancellationCommand
import dev.tramai.spring.sovereign.ops.WorkflowCancellationControlPlane
import dev.tramai.spring.sovereign.ops.WorkflowCancellationResult
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Preview REST control plane for workflow cancellation operations (0.7.7m).
 *
 * Disabled by default -- enable via `tramai.sovereign.ops.rest-control-plane-enabled=true`.
 *
 * Endpoint: `POST /tramai/sovereign/workflows/{workflowName}/runs/{runId}/cancel`
 *
 * Strictly fail-closed: delegates authorization, precondition verification, and CAS mutation
 * to [WorkflowCancellationControlPlane].
 */
@RestController
@RequestMapping("/tramai/sovereign/workflows")
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops",
    name = ["rest-control-plane-enabled"],
    havingValue = "true",
)
@ConditionalOnBean(WorkflowCancellationControlPlane::class)
class WorkflowCancellationControlPlaneController(
    private val controlPlane: WorkflowCancellationControlPlane,
) {
    @PostMapping("/{workflowName}/runs/{runId}/cancel")
    fun cancel(
        @PathVariable("workflowName") workflowName: String,
        @PathVariable("runId") runId: String,
        @RequestBody request: CancelWorkflowRunRequest,
        authority: OperatorAuthority,
    ): ResponseEntity<WorkflowCancellationResponse> =
        runBlocking {
            if (workflowName.isBlank() || runId.isBlank()) {
                return@runBlocking ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    WorkflowCancellationResponse(
                        status = "INVALID_REQUEST",
                        workflowName = workflowName,
                        runId = runId,
                        message = "tramai-cancellation-blank-target",
                    ),
                )
            }

            val expectedRevision = request.expectedRevision
            if (expectedRevision == null || expectedRevision < 0) {
                return@runBlocking ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    WorkflowCancellationResponse(
                        status = "INVALID_REQUEST",
                        workflowName = workflowName,
                        runId = runId,
                        expectedRevision = expectedRevision,
                        message = "tramai-cancellation-invalid-expected-revision",
                    ),
                )
            }

            val reason = request.reason
            if (reason.isNullOrBlank()) {
                return@runBlocking ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    WorkflowCancellationResponse(
                        status = "INVALID_REQUEST",
                        workflowName = workflowName,
                        runId = runId,
                        message = "tramai-cancellation-blank-reason",
                    ),
                )
            }

            val command =
                WorkflowCancellationCommand(
                    workflowName = workflowName,
                    runId = runId,
                    expectedRevision = expectedRevision,
                    reason = reason,
                    correlationId = request.correlationId,
                )

            val result = controlPlane.cancel(command, authority)
            mapCancellationResult(result, workflowName, runId)
        }

    private fun mapCancellationResult(
        result: WorkflowCancellationResult,
        workflowName: String,
        runId: String,
    ): ResponseEntity<WorkflowCancellationResponse> =
        when (result) {
            is WorkflowCancellationResult.Cancelled -> {
                ResponseEntity.ok(
                    WorkflowCancellationResponse(
                        status = result.status,
                        workflowName = workflowName,
                        runId = runId,
                        revision = result.revision,
                        cancelledBy = result.cancelledBy,
                        cancelledAtEpochMillis = result.cancelledAtEpochMillis,
                        alreadyCancelled = result.alreadyCancelled,
                    ),
                )
            }

            is WorkflowCancellationResult.Stale -> {
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    WorkflowCancellationResponse(
                        status = "STALE",
                        workflowName = workflowName,
                        runId = runId,
                        expectedRevision = result.expectedRevision,
                        currentRevision = result.currentRevision,
                        message = "tramai-workflow-version-conflict",
                    ),
                )
            }

            is WorkflowCancellationResult.NotFound -> {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    WorkflowCancellationResponse(
                        status = "NOT_FOUND",
                        workflowName = workflowName,
                        runId = runId,
                        message = "tramai-workflow-not-found",
                    ),
                )
            }

            is WorkflowCancellationResult.Unauthorized -> {
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    WorkflowCancellationResponse(
                        status = "UNAUTHORIZED",
                        workflowName = workflowName,
                        runId = runId,
                        message = result.reason.wireName,
                    ),
                )
            }

            is WorkflowCancellationResult.Conflict -> {
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    WorkflowCancellationResponse(
                        status = "CONFLICT",
                        workflowName = workflowName,
                        runId = runId,
                        message = result.reason,
                    ),
                )
            }
        }
}
