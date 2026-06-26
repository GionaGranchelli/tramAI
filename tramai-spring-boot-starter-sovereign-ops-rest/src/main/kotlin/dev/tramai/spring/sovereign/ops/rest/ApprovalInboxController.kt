package dev.tramai.spring.sovereign.ops.rest

import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxPage
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQuery
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQueryService
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxWorkItem
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Preview REST inbox API for approval work queues.
 *
 * Disabled by default — enable via `tramai.sovereign.ops.rest-control-plane-enabled=true`.
 */
@RestController
@RequestMapping("\${tramai.sovereign.ops.rest.base-path:/tramai/sovereign/approvals}")
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops",
    name = ["rest-control-plane-enabled"],
    havingValue = "true",
)
@ConditionalOnBean(ApprovalInboxQueryService::class)
class ApprovalInboxController(
    private val queryService: ApprovalInboxQueryService,
) {

    @GetMapping
    fun search(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) requiredRole: String?,
        @RequestParam(required = false) requestedBy: String?,
        @RequestParam(required = false) expiresBefore: Instant?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ApprovalInboxListResponse = runBlocking {
        if (limit < 1 || limit > 100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "approval-inbox-limit-out-of-range")
        }
        if (requiredRole != null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "approval-inbox-required-role-filter-not-supported",
            )
        }
        val query = ApprovalInboxQuery(
            status = try {
                status?.let { dev.tramai.core.approval.ApprovalStatus.valueOf(it) }
                    ?: dev.tramai.core.approval.ApprovalStatus.PENDING
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid-approval-inbox-status")
            },
            requiredRole = null,
            requestedBy = requestedBy,
            expiresBefore = expiresBefore,
            limit = limit,
            cursor = cursor,
        )
        try {
            queryService.search(query).toListResponse()
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid-approval-inbox-request", e)
        }
    }

    @GetMapping("/{approvalId}/work-item")
    fun getWorkItem(
        @PathVariable approvalId: String,
    ): ResponseEntity<ApprovalWorkItemResponse> = runBlocking {
        val item = queryService.getWorkItem(
            parseOrBadRequest { dev.tramai.core.approval.gateway.ApprovalId(approvalId) },
        ) ?: return@runBlocking ResponseEntity.notFound().build()
        ResponseEntity.ok(item.toWorkItemResponse())
    }
}

private fun ApprovalInboxPage.toListResponse(): ApprovalInboxListResponse =
    ApprovalInboxListResponse(
        items = items.map(ApprovalInboxWorkItem::toItemResponse),
        nextCursor = nextCursor,
    )

private fun ApprovalInboxWorkItem.toItemResponse(): ApprovalInboxItemResponse =
    ApprovalInboxItemResponse(
        approvalId = approvalId.value,
        workflowRunId = workflowRunId,
        toolName = toolName,
        status = status.name,
        requestedBy = requestedBy,
        requestedAt = requestedAt,
        expiresAt = expiresAt,
        requiredRole = requiredRole?.value,
        riskLevel = riskLevel,
        subjectType = subjectType,
        subjectId = subjectId,
        recommendationType = recommendationType,
        continuationStatus = continuationStatus?.name,
        version = version,
    )

private fun ApprovalInboxWorkItem.toWorkItemResponse(): ApprovalWorkItemResponse =
    ApprovalWorkItemResponse(
        approvalId = approvalId.value,
        workflowRunId = workflowRunId,
        toolName = toolName,
        status = status.name,
        requestedBy = requestedBy,
        requestedAt = requestedAt,
        expiresAt = expiresAt,
        requiredRole = requiredRole?.value,
        riskLevel = riskLevel,
        subjectType = subjectType,
        subjectId = subjectId,
        recommendationType = recommendationType,
        continuationStatus = continuationStatus?.name,
        version = version,
    )
