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
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.core.approval.ValidateResumeCommand
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException

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
        validateActorId(command.requestedBy)

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            observeFailure("createApproval", approvalId, e)
            throw ApprovalCreationException(approvalId)
        }

        return ApprovalChallenge(
            approvalId = approvalId,
            token = token,
            expiresAt = command.expiresAt,
        )
    }

    override suspend fun cancelApproval(
        approvalId: String,
        expectedVersion: Long,
        reason: String,
    ) {
        validateIdField(approvalId, "approvalId")
        require(expectedVersion >= 0) { "expectedVersion must be non-negative" }
        require(expectedVersion < Long.MAX_VALUE) { "expectedVersion must be less than Long.MAX_VALUE" }
        validateIdField(reason, "reason")

        try {
            store.transition(
                approvalId = approvalId,
                expectedVersion = expectedVersion,
                transition = ApprovalTransition.Deny(
                    decidedBy = "system",
                    comment = "cancelled: $reason",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            observeFailure("cancelApproval", approvalId, e)
            throw mapStoreError(approvalId, e)
        }
    }

    override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
        val (request, presentedTokenDigest) = prepareResumeAuthorization(
            approvalId = command.approvalId,
            expectedVersion = command.expectedVersion,
            presentedToken = command.presentedToken,
            consumedBy = command.consumedBy,
            workflowRunId = command.workflowRunId,
            toolName = command.toolName,
            argumentsDigest = command.argumentsDigest,
            policyVersion = command.policyVersion,
            workflowDigest = command.workflowDigest,
            operationName = "authorizeResume",
        )

        val receipt = try {
            store.consumeApprovedOrReplay(
                approvalId = command.approvalId,
                expectedVersion = command.expectedVersion,
                presentedTokenDigest = presentedTokenDigest,
                consumedBy = command.consumedBy,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            observeFailure("authorizeResume.consumeApprovedOrReplay", command.approvalId, e)
            throw mapStoreError(command.approvalId, e)
        }
        val consumed = receipt.request

        if (consumed.approvalId != command.approvalId) throw ApprovalAuthorizationException(command.approvalId)
        if (consumed.binding != request.binding) throw ApprovalAuthorizationException(command.approvalId)
        if (consumed.status != ApprovalStatus.APPROVED) throw ApprovalAuthorizationException(command.approvalId)
        if (consumed.consumedBy != command.consumedBy) throw ApprovalAuthorizationException(command.approvalId)
        if (consumed.consumedAt == null) throw ApprovalAuthorizationException(command.approvalId)
        val expectedConsumedVersion = try {
            Math.addExact(command.expectedVersion, 1L)
        } catch (_: ArithmeticException) {
            throw ApprovalAuthorizationException(command.approvalId)
        }
        if (consumed.version != expectedConsumedVersion) throw ApprovalAuthorizationException(command.approvalId)

        return ApprovalAuthorization(
            approvalId = consumed.approvalId,
            consumedBy = consumed.consumedBy!!,
            consumedAt = consumed.consumedAt!!,
            version = consumed.version,
            replayed = receipt.replayed,
        )
    }

    override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
        val (request, _) = prepareResumeAuthorization(
            approvalId = command.approvalId,
            expectedVersion = command.expectedVersion,
            presentedToken = command.presentedToken,
            consumedBy = command.consumedBy,
            workflowRunId = command.workflowRunId,
            toolName = command.toolName,
            argumentsDigest = command.argumentsDigest,
            policyVersion = command.policyVersion,
            workflowDigest = command.workflowDigest,
            operationName = "validateResume",
        )

        return ApprovalValidation(
            approvalId = request.approvalId,
            validatedBy = command.consumedBy,
            validatedAt = clock.instant(),
            version = command.expectedVersion,
        )
    }

    private fun revalidateBinding(
        request: ApprovalRequest,
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        argumentsDigest: dev.tramai.core.approval.Sha256Digest,
        policyVersion: String,
        workflowDigest: dev.tramai.core.approval.Sha256Digest,
    ) {
        if (request.binding.workflowRunId != workflowRunId) {
            throw ApprovalBindingMismatchException(approvalId, "workflowRunId")
        }
        if (request.binding.toolName != toolName) {
            throw ApprovalBindingMismatchException(approvalId, "toolName")
        }
        if (request.binding.argumentsDigest != argumentsDigest) {
            throw ApprovalBindingMismatchException(approvalId, "argumentsDigest")
        }
        if (request.binding.policyVersion != policyVersion) {
            throw ApprovalBindingMismatchException(approvalId, "policyVersion")
        }
        if (request.binding.workflowDigest != workflowDigest) {
            throw ApprovalBindingMismatchException(approvalId, "workflowDigest")
        }
    }

    private suspend fun prepareResumeAuthorization(
        approvalId: String,
        expectedVersion: Long,
        presentedToken: dev.tramai.core.approval.ApprovalToken,
        consumedBy: String,
        workflowRunId: String,
        toolName: String,
        argumentsDigest: dev.tramai.core.approval.Sha256Digest,
        policyVersion: String,
        workflowDigest: dev.tramai.core.approval.Sha256Digest,
        operationName: String,
    ): Pair<ApprovalRequest, dev.tramai.core.approval.Sha256Digest> {
        validateIdField(approvalId, "approvalId")
        validateActorId(consumedBy)
        validateIdField(workflowRunId, "workflowRunId")
        validateIdField(toolName, "toolName")
        validateIdField(policyVersion, "policyVersion")
        validateIdField(workflowDigest.value, "workflowDigest")
        require(expectedVersion >= 0) { "expectedVersion must be non-negative" }
        require(expectedVersion < Long.MAX_VALUE) { "expectedVersion must be less than Long.MAX_VALUE" }

        val request = try {
            store.get(approvalId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            observeFailure("$operationName.get", approvalId, e)
            throw mapStoreError(approvalId, e)
        } ?: throw ApprovalNotFoundException(approvalId)

        revalidateBinding(
            request = request,
            approvalId = approvalId,
            workflowRunId = workflowRunId,
            toolName = toolName,
            argumentsDigest = argumentsDigest,
            policyVersion = policyVersion,
            workflowDigest = workflowDigest,
        )
        val presentedTokenDigest = approvalTokenDigester.digest(presentedToken)
        validateAuthorizationCandidate(
            request = request,
            approvalId = approvalId,
            expectedVersion = expectedVersion,
            presentedTokenDigest = presentedTokenDigest,
            consumedBy = consumedBy,
        )
        decisionValidator.validate(request, consumedBy)
        return request to presentedTokenDigest
    }

    private fun validateAuthorizationCandidate(
        request: ApprovalRequest,
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: dev.tramai.core.approval.Sha256Digest,
        consumedBy: String,
    ) {
        if (!tokenDigestsMatch(presentedTokenDigest, request.binding.approvalTokenDigest)) {
            throw ApprovalTokenRejectedException(approvalId)
        }
        if (request.status != ApprovalStatus.APPROVED) {
            throw ApprovalAuthorizationException(approvalId)
        }

        if (request.consumedAt == null && request.consumedBy == null) {
            if (request.version != expectedVersion) {
                throw ApprovalAuthorizationException(approvalId)
            }
            if (!clock.instant().isBefore(request.expiresAt)) {
                throw ApprovalAuthorizationException(approvalId)
            }
            return
        }

        if (request.consumedAt == null || request.consumedBy == null) {
            throw ApprovalAuthorizationException(approvalId)
        }
        if (request.consumedBy != consumedBy) {
            throw ApprovalAuthorizationException(approvalId)
        }
        val replayVersion = try {
            Math.addExact(expectedVersion, 1L)
        } catch (_: ArithmeticException) {
            throw ApprovalAuthorizationException(approvalId)
        }
        if (request.version != replayVersion) {
            throw ApprovalAuthorizationException(approvalId)
        }
    }

    private fun mapStoreError(
        approvalId: String,
        exception: Exception,
    ): RuntimeException {
        return when (exception) {
            is ApprovalStoreNotFoundException -> ApprovalNotFoundException(approvalId)
            is ApprovalStoreTokenRejectedException -> ApprovalTokenRejectedException(approvalId)
            is ApprovalStoreConflictException -> ApprovalAuthorizationException(approvalId)
            is ApprovalStoreNotConsumableException -> ApprovalAuthorizationException(approvalId)
            else -> ApprovalAuthorizationException(approvalId)
        }
    }

    private fun observeFailure(
        operation: String,
        approvalId: String?,
        failure: Exception,
    ) {
        try {
            failureObserver?.record(operation, approvalId, failure as? RuntimeException ?: RuntimeException(failure))
        } catch (_: RuntimeException) {
            // Diagnostic observers must not replace safe public failures.
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

    private fun validateActorId(value: String) {
        SafeActorIdPolicy.validateActorId(value, "actorId")
    }

    private fun tokenDigestsMatch(
        presentedTokenDigest: dev.tramai.core.approval.Sha256Digest,
        storedTokenDigest: dev.tramai.core.approval.Sha256Digest,
    ): Boolean =
        MessageDigest.isEqual(
            presentedTokenDigest.value.toByteArray(StandardCharsets.US_ASCII),
            storedTokenDigest.value.toByteArray(StandardCharsets.US_ASCII),
        )
}
