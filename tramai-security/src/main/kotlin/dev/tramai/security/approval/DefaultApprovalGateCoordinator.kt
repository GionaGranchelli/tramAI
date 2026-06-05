package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalDecisionValidator
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalIdGenerator
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTokenDigester
import dev.tramai.core.approval.ApprovalTokenGenerator
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.exception.ApprovalAuthorizationException
import dev.tramai.core.exception.ApprovalBindingMismatchException
import dev.tramai.core.exception.ApprovalCreationException
import dev.tramai.core.exception.ApprovalFailureObserver
import dev.tramai.core.exception.ApprovalNotFoundException
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotConsumableException
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.ApprovalStoreTokenRejectedException
import dev.tramai.core.exception.ApprovalTokenRejectedException
import java.time.Clock
import java.time.Duration

class DefaultApprovalGateCoordinator(
    private val store: ApprovalStore,
    private val approvalIdGenerator: ApprovalIdGenerator,
    private val approvalTokenGenerator: ApprovalTokenGenerator,
    private val approvalTokenDigester: ApprovalTokenDigester,
    private val decisionValidator: ApprovalDecisionValidator = AllowAnyApprovalDecisionValidator,
    private val clock: Clock = Clock.systemUTC(),
    private val maxIdLength: Int = 256,
    private val maxApprovalTtl: Duration = Duration.ofMinutes(15),
    private val failureObserver: ApprovalFailureObserver? = null,
) : ApprovalGateCoordinator {

    init {
        require(maxApprovalTtl > Duration.ZERO) { "maxApprovalTtl must be positive" }
    }

    override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
        validateIdField(command.workflowRunId, "workflowRunId")
        validateIdField(command.toolName, "toolName")
        validateIdField(command.policyVersion, "policyVersion")
        validateIdField(command.requestedBy, "requestedBy")

        val now = clock.instant()
        require(command.expiresAt > now) { "expiresAt must be in the future" }

        val maxExpiry = now.plus(maxApprovalTtl)
        require(!command.expiresAt.isAfter(maxExpiry)) {
            "expiresAt must be within $maxApprovalTtl of now"
        }

        val approvalId = validateIdField(approvalIdGenerator.generate(), "approvalId")
        val token = approvalTokenGenerator.generate()
        val tokenDigest = approvalTokenDigester.digest(token)

        val request = ApprovalRequest(
            approvalId = approvalId,
            binding = ApprovalBinding(
                workflowRunId = command.workflowRunId,
                toolName = command.toolName,
                argumentsDigest = command.argumentsDigest,
                policyVersion = command.policyVersion,
                workflowDigest = command.workflowDigest,
                approvalTokenDigest = tokenDigest,
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = command.requestedBy,
            requestedAt = now,
            expiresAt = command.expiresAt,
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        try {
            store.create(request)
        } catch (e: RuntimeException) {
            failureObserver?.record("createApproval", approvalId, e)
            throw when (e) {
                is ApprovalStoreConflictException -> ApprovalCreationException(approvalId)
                else -> ApprovalCreationException(approvalId)
            }
        }

        return ApprovalChallenge(
            approvalId = approvalId,
            token = token,
            expiresAt = command.expiresAt,
        )
    }

    override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
        validateIdField(command.approvalId, "approvalId")
        validateIdField(command.consumedBy, "consumedBy")
        validateIdField(command.workflowRunId, "workflowRunId")
        validateIdField(command.toolName, "toolName")
        validateIdField(command.policyVersion, "policyVersion")
        require(command.expectedVersion >= 0) { "expectedVersion must be non-negative" }

        val request = try {
            store.get(command.approvalId)
        } catch (e: RuntimeException) {
            failureObserver?.record("authorizeResume.get", command.approvalId, e)
            throw mapStoreError(command.approvalId, e)
        } ?: throw ApprovalNotFoundException(command.approvalId)

        revalidateBinding(request, command)
        val presentedTokenDigest = approvalTokenDigester.digest(command.presentedToken)
        decisionValidator.validate(request, command.consumedBy)

        val consumed = try {
            store.consumeApproved(
                approvalId = command.approvalId,
                expectedVersion = command.expectedVersion,
                presentedTokenDigest = presentedTokenDigest,
                consumedBy = command.consumedBy,
            )
        } catch (e: RuntimeException) {
            failureObserver?.record("authorizeResume.consumeApproved", command.approvalId, e)
            throw mapStoreError(command.approvalId, e)
        }

        val consumedBy = consumed.consumedBy
            ?: throw ApprovalAuthorizationException(consumed.approvalId)
        val consumedAt = consumed.consumedAt
            ?: throw ApprovalAuthorizationException(consumed.approvalId)

        return ApprovalAuthorization(
            approvalId = consumed.approvalId,
            consumedBy = consumedBy,
            consumedAt = consumedAt,
            version = consumed.version,
        )
    }

    private fun revalidateBinding(
        request: ApprovalRequest,
        command: AuthorizeResumeCommand,
    ) {
        if (request.binding.workflowRunId != command.workflowRunId) {
            throw ApprovalBindingMismatchException(command.approvalId, "workflowRunId")
        }
        if (request.binding.toolName != command.toolName) {
            throw ApprovalBindingMismatchException(command.approvalId, "toolName")
        }
        if (request.binding.argumentsDigest != command.argumentsDigest) {
            throw ApprovalBindingMismatchException(command.approvalId, "argumentsDigest")
        }
        if (request.binding.policyVersion != command.policyVersion) {
            throw ApprovalBindingMismatchException(command.approvalId, "policyVersion")
        }
        if (request.binding.workflowDigest != command.workflowDigest) {
            throw ApprovalBindingMismatchException(command.approvalId, "workflowDigest")
        }
    }

    private fun mapStoreError(
        approvalId: String,
        exception: RuntimeException,
    ): RuntimeException {
        return when (exception) {
            is ApprovalStoreNotFoundException -> ApprovalNotFoundException(approvalId)
            is ApprovalStoreTokenRejectedException -> ApprovalTokenRejectedException(approvalId)
            is ApprovalStoreConflictException -> ApprovalAuthorizationException(approvalId)
            is ApprovalStoreNotConsumableException -> ApprovalAuthorizationException(approvalId)
            else -> ApprovalAuthorizationException(approvalId)
        }
    }

    private fun validateIdField(value: String, fieldName: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
        require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(trimmed.length <= maxIdLength) { "$fieldName exceeds maximum length of $maxIdLength" }
        require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
        return trimmed
    }
}
