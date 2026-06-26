package dev.tramai.spring.sovereign.ops.rest

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalResumeCommand
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeResult
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Preview REST control plane for approval operations.
 *
 * Disabled by default — enable via `tramai.sovereign.ops.rest-control-plane-enabled=true`.
 *
 * Delegates to [ApprovalDecisionControlPlane] and [ApprovalResumeControlPlane].
 * Does not duplicate decision/resume logic.
 *
 * Has its own [ConditionalOnProperty] guard so that component scanning
 * cannot register the controller when the property is unset or false.
 */
@RestController
@RequestMapping("\${tramai.sovereign.ops.rest.base-path:/tramai/sovereign/approvals}")
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops",
    name = ["rest-control-plane-enabled"],
    havingValue = "true",
)
@ConditionalOnBean(value = [ApprovalDecisionControlPlane::class, ApprovalResumeControlPlane::class])
class ApprovalControlPlaneController(
    private val decisionControlPlane: ApprovalDecisionControlPlane,
    private val resumeControlPlane: ApprovalResumeControlPlane,
    private val approvalStore: ApprovalStore,
    private val approvalContinuationStore: ApprovalContinuationStore,
) {

    @GetMapping("/{approvalId}")
    fun getApproval(
        @PathVariable approvalId: String,
    ): ResponseEntity<ApprovalStatusResponse> = runBlocking {
        val approval = approvalStore.get(approvalId)
            ?: return@runBlocking ResponseEntity.notFound().build()
        val continuation = approvalContinuationStore.get(approvalId)
        ResponseEntity.ok(
            ApprovalStatusResponse(
                approvalId = approvalId,
                status = approval.status.name,
                version = approval.version,
                requestedBy = approval.requestedBy,
                requestedAt = approval.requestedAt,
                expiresAt = approval.expiresAt,
                decidedBy = approval.decidedBy,
                decidedAt = approval.decidedAt,
                continuationStatus = continuation?.status?.name,
            ),
        )
    }

    @PostMapping("/{approvalId}/approve")
    fun approve(
        @PathVariable approvalId: String,
        @RequestBody request: ApproveDenyRequest,
    ): ResponseEntity<ApprovalControlPlaneResponse> = runBlocking {
        val result = decisionControlPlane.approve(
            ApprovalDecisionCommand(
                approvalId = parseOrBadRequest { ApprovalId(approvalId) },
                actorId = request.actorId,
                actorRole = parseOrBadRequest { ApproverRole(request.actorRole) },
                comment = request.comment,
                expectedVersion = request.expectedVersion,
                correlationId = request.correlationId,
            ),
        )
        mapDecisionResult(result, approvalId)
    }

    @PostMapping("/{approvalId}/deny")
    fun deny(
        @PathVariable approvalId: String,
        @RequestBody request: ApproveDenyRequest,
    ): ResponseEntity<ApprovalControlPlaneResponse> = runBlocking {
        val result = decisionControlPlane.deny(
            ApprovalDecisionCommand(
                approvalId = parseOrBadRequest { ApprovalId(approvalId) },
                actorId = request.actorId,
                actorRole = parseOrBadRequest { ApproverRole(request.actorRole) },
                comment = request.comment,
                expectedVersion = request.expectedVersion,
                correlationId = request.correlationId,
            ),
        )
        mapDecisionResult(result, approvalId)
    }

    @PostMapping("/{approvalId}/resume")
    fun resume(
        @PathVariable approvalId: String,
        @RequestBody request: ResumeRequest,
    ): ResponseEntity<ApprovalControlPlaneResponse> = runBlocking {
        val result = resumeControlPlane.resume(
            ApprovalResumeCommand(
                approvalId = parseOrBadRequest { ApprovalId(approvalId) },
                resumeToken = parseOrBadRequest { ResumeToken(request.resumeToken) },
                resumedBy = request.resumedBy,
                expectedApprovalVersion = request.expectedApprovalVersion,
                expectedContinuationVersion = request.expectedContinuationVersion,
            ),
        )
        mapResumeResult(result, approvalId)
    }

    // -- helpers --

    private fun mapDecisionResult(
        result: ApprovalDecisionResult,
        approvalId: String,
    ): ResponseEntity<ApprovalControlPlaneResponse> = when (result) {
        is ApprovalDecisionResult.Approved -> ResponseEntity.ok(
            ApprovalControlPlaneResponse(
                status = "APPROVED",
                approvalId = approvalId,
                actorId = result.decidedBy,
                version = result.version,
            ),
        )
        is ApprovalDecisionResult.Denied -> ResponseEntity.ok(
            ApprovalControlPlaneResponse(
                status = "DENIED",
                approvalId = approvalId,
                actorId = result.decidedBy,
                version = result.version,
            ),
        )
        is ApprovalDecisionResult.AlreadyApproved -> ResponseEntity.ok(
            ApprovalControlPlaneResponse(
                status = "ALREADY_APPROVED",
                approvalId = approvalId,
                actorId = result.decidedBy,
            ),
        )
        is ApprovalDecisionResult.AlreadyDenied -> ResponseEntity.ok(
            ApprovalControlPlaneResponse(
                status = "ALREADY_DENIED",
                approvalId = approvalId,
                actorId = result.decidedBy,
            ),
        )
        is ApprovalDecisionResult.Expired -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApprovalControlPlaneResponse(
                status = "EXPIRED",
                approvalId = approvalId,
            ),
        )
        is ApprovalDecisionResult.NotFound -> ResponseEntity.notFound().build()
        is ApprovalDecisionResult.Conflict -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApprovalControlPlaneResponse(
                status = "CONFLICT",
                approvalId = approvalId,
                message = result.reason,
            ),
        )
    }

    private fun mapResumeResult(
        result: ApprovalResumeResult,
        approvalId: String,
    ): ResponseEntity<ApprovalControlPlaneResponse> = when (result) {
        is ApprovalResumeResult.Resumed -> ResponseEntity.ok(
            ApprovalControlPlaneResponse(
                status = "RESUMED",
                approvalId = approvalId,
                actorId = result.resumedBy,
            ),
        )
        is ApprovalResumeResult.NotFound -> ResponseEntity.notFound().build()
        is ApprovalResumeResult.NotApproved -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApprovalControlPlaneResponse(
                status = "NOT_APPROVED",
                approvalId = approvalId,
                message = "approval-status-${result.status.name}",
            ),
        )
        is ApprovalResumeResult.AlreadyCompleted -> ResponseEntity.ok(
            ApprovalControlPlaneResponse(
                status = "ALREADY_COMPLETED",
                approvalId = approvalId,
            ),
        )
        is ApprovalResumeResult.Conflict -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApprovalControlPlaneResponse(
                status = "CONFLICT",
                approvalId = approvalId,
                message = result.reason,
            ),
        )
        is ApprovalResumeResult.Failed -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApprovalControlPlaneResponse(
                status = "FAILED",
                approvalId = approvalId,
                message = "approval-resume-failed",
            ),
        )
    }
}

/**
 * Wraps value-type construction and converts [IllegalArgumentException]
 * to a 400 Bad Request response.
 */
private fun <T> parseOrBadRequest(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid-approval-control-plane-request")
    }
