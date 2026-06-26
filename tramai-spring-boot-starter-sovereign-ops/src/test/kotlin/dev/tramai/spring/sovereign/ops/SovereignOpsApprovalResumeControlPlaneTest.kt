package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalConsumptionReceipt
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.engine.ResumeApprovalCommand
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SovereignOpsApprovalResumeControlPlaneTest {

    private val now: Instant = Instant.parse("2026-06-25T10:15:30Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `resume approved approval returns Resumed with workflow result`() = runBlocking {
        val approvalStore = ResumeMutableApprovalStore(
            mutableMapOf("approval-1" to approvedApproval("approval-1")),
        )
        val continuationStore = ResumeMutableApprovalContinuationStore(
            mutableMapOf("approval-1" to pendingContinuation("approval-1")),
        )
        var capturedCommand: ResumeApprovalCommand? = null
        val controlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = approvalStore,
            approvalContinuationStore = continuationStore,
            resumeApproval = { command ->
                capturedCommand = command
                "workflow-result"
            },
            clock = clock,
        )

        val result = controlPlane.resume(resumeCommand("approval-1"))

        assertThat(result).isEqualTo(
            ApprovalResumeResult.Resumed(
                approvalId = ApprovalId("approval-1"),
                resumedBy = "operator-1",
                result = "workflow-result",
            ),
        )
        assertThat(capturedCommand).isEqualTo(
            ResumeApprovalCommand(
                approvalId = "approval-1",
                approvalExpectedVersion = 1L,
                continuationExpectedVersion = 0L,
                presentedToken = ApprovalToken.parsePresented("resume-token-1"),
                resumedBy = "operator-1",
            ),
        )
    }

    @Test
    fun `resume missing approval returns NotFound`() = runBlocking {
        val controlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = ResumeMutableApprovalStore(),
            approvalContinuationStore = ResumeMutableApprovalContinuationStore(),
            resumeApproval = { error("should not run") },
            clock = clock,
        )

        val result = controlPlane.resume(resumeCommand("approval-404"))

        assertThat(result).isEqualTo(ApprovalResumeResult.NotFound(ApprovalId("approval-404")))
    }

    @Test
    fun `resume pending approval returns NotApproved`() = runBlocking {
        val approvalStore = ResumeMutableApprovalStore(
            mutableMapOf("approval-1" to approval("approval-1", ApprovalStatus.PENDING)),
        )
        val controlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = approvalStore,
            approvalContinuationStore = ResumeMutableApprovalContinuationStore(),
            resumeApproval = { error("should not run") },
            clock = clock,
        )

        val result = controlPlane.resume(resumeCommand("approval-1"))

        assertThat(result).isEqualTo(
            ApprovalResumeResult.NotApproved(ApprovalId("approval-1"), ApprovalStatus.PENDING),
        )
    }

    @Test
    fun `resume denied approval returns NotApproved`() = runBlocking {
        val approvalStore = ResumeMutableApprovalStore(
            mutableMapOf("approval-1" to approval("approval-1", ApprovalStatus.DENIED, version = 1L)),
        )
        val controlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = approvalStore,
            approvalContinuationStore = ResumeMutableApprovalContinuationStore(),
            resumeApproval = { error("should not run") },
            clock = clock,
        )

        val result = controlPlane.resume(resumeCommand("approval-1"))

        assertThat(result).isEqualTo(
            ApprovalResumeResult.NotApproved(ApprovalId("approval-1"), ApprovalStatus.DENIED),
        )
    }

    @Test
    fun `resume expired pending approval returns NotApproved while approved expired approval can still resume`() = runBlocking {
        val pendingApprovalStore = ResumeMutableApprovalStore(
            mutableMapOf(
                "pending-expired" to approval(
                    approvalId = "pending-expired",
                    status = ApprovalStatus.PENDING,
                    expiresAt = now.minusSeconds(1),
                ),
            ),
        )
        val pendingControlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = pendingApprovalStore,
            approvalContinuationStore = ResumeMutableApprovalContinuationStore(),
            resumeApproval = { error("should not run") },
            clock = clock,
        )

        val pendingResult = pendingControlPlane.resume(resumeCommand("pending-expired"))

        assertThat(pendingResult).isEqualTo(
            ApprovalResumeResult.NotApproved(ApprovalId("pending-expired"), ApprovalStatus.PENDING),
        )

        val approvedApprovalStore = ResumeMutableApprovalStore(
            mutableMapOf(
                "approved-expired" to approvedApproval("approved-expired").copy(expiresAt = now.minusSeconds(1)),
            ),
        )
        val continuationStore = ResumeMutableApprovalContinuationStore(
            mutableMapOf("approved-expired" to pendingContinuation("approved-expired", approvalExpiresAt = now.plusSeconds(60))),
        )
        val approvedControlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = approvedApprovalStore,
            approvalContinuationStore = continuationStore,
            resumeApproval = { "resumed-after-approval" },
            clock = clock,
        )

        val approvedResult = approvedControlPlane.resume(resumeCommand("approved-expired"))

        assertThat(approvedResult).isEqualTo(
            ApprovalResumeResult.Resumed(
                approvalId = ApprovalId("approved-expired"),
                resumedBy = "operator-1",
                result = "resumed-after-approval",
            ),
        )
    }

    @Test
    fun `resume missing continuation returns Conflict`() = runBlocking {
        val approvalStore = ResumeMutableApprovalStore(
            mutableMapOf("approval-1" to approvedApproval("approval-1")),
        )
        val controlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = approvalStore,
            approvalContinuationStore = ResumeMutableApprovalContinuationStore(),
            resumeApproval = { error("should not run") },
            clock = clock,
        )

        val result = controlPlane.resume(resumeCommand("approval-1"))

        assertThat(result).isEqualTo(
            ApprovalResumeResult.Conflict(
                approvalId = ApprovalId("approval-1"),
                reason = "approval-continuation-missing",
            ),
        )
    }

    @Test
    fun `resume completed continuation returns AlreadyCompleted`() = runBlocking {
        val approvalStore = ResumeMutableApprovalStore(
            mutableMapOf("approval-1" to approvedApproval("approval-1")),
        )
        val continuationStore = ResumeMutableApprovalContinuationStore(
            mutableMapOf(
                "approval-1" to pendingContinuation("approval-1").copy(status = ApprovalContinuationStatus.COMPLETED),
            ),
        )
        val controlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = approvalStore,
            approvalContinuationStore = continuationStore,
            resumeApproval = { error("should not run") },
            clock = clock,
        )

        val result = controlPlane.resume(resumeCommand("approval-1"))

        assertThat(result).isEqualTo(ApprovalResumeResult.AlreadyCompleted(ApprovalId("approval-1")))
    }

    @Test
    fun `resume with invalid resume token returns Conflict`() = runBlocking {
        val approvalStore = ResumeMutableApprovalStore(
            mutableMapOf("approval-1" to approvedApproval("approval-1")),
        )
        val continuationStore = ResumeMutableApprovalContinuationStore(
            mutableMapOf("approval-1" to pendingContinuation("approval-1")),
        )
        val controlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = approvalStore,
            approvalContinuationStore = continuationStore,
            resumeApproval = { error("should not run") },
            clock = clock,
        )

        val result = controlPlane.resume(
            resumeCommand("approval-1").copy(resumeToken = ResumeToken("bad token")),
        )

        assertThat(result).isEqualTo(
            ApprovalResumeResult.Conflict(
                approvalId = ApprovalId("approval-1"),
                reason = "approval-resume-token-invalid",
            ),
        )
    }

    @Test
    fun `runtime exception returns Failed without deleting continuation`() = runBlocking {
        val approvalStore = ResumeMutableApprovalStore(
            mutableMapOf("approval-1" to approvedApproval("approval-1")),
        )
        val continuationStore = ResumeMutableApprovalContinuationStore(
            mutableMapOf("approval-1" to pendingContinuation("approval-1")),
        )
        val controlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = approvalStore,
            approvalContinuationStore = continuationStore,
            resumeApproval = { throw RuntimeException("boom") },
            clock = clock,
        )

        val result = controlPlane.resume(resumeCommand("approval-1"))

        assertThat(result).isEqualTo(
            ApprovalResumeResult.Failed(
                approvalId = ApprovalId("approval-1"),
                reason = "boom",
            ),
        )
        assertThat(continuationStore.get("approval-1")).isEqualTo(pendingContinuation("approval-1"))
    }

    @Test
    fun `cancellation exception is rethrown`() = runBlocking {
        val approvalStore = ResumeMutableApprovalStore(
            mutableMapOf("approval-1" to approvedApproval("approval-1")),
        )
        val continuationStore = ResumeMutableApprovalContinuationStore(
            mutableMapOf("approval-1" to pendingContinuation("approval-1")),
        )
        val controlPlane = SovereignOpsApprovalResumeControlPlane(
            approvalStore = approvalStore,
            approvalContinuationStore = continuationStore,
            resumeApproval = { throw CancellationException("cancelled") },
            clock = clock,
        )

        assertThatThrownBy {
            runBlocking { controlPlane.resume(resumeCommand("approval-1")) }
        }.isInstanceOf(CancellationException::class.java)
    }

    private fun resumeCommand(approvalId: String): ApprovalResumeCommand =
        ApprovalResumeCommand(
            approvalId = ApprovalId(approvalId),
            resumeToken = ResumeToken("resume-token-1"),
            resumedBy = "operator-1",
        )

    private fun approval(
        approvalId: String,
        status: ApprovalStatus,
        expiresAt: Instant = now.plusSeconds(600),
        version: Long = 0L,
    ): ApprovalRequest = ApprovalRequest(
        approvalId = approvalId,
        binding = ApprovalBinding(
            workflowRunId = "wf-$approvalId",
            toolName = "claim-payout",
            argumentsDigest = digest("01"),
            policyVersion = "v1",
            workflowDigest = digest("02"),
            approvalTokenDigest = digest("03"),
        ),
        status = status,
        requestedBy = "triage-system",
        requestedAt = now.minusSeconds(30),
        expiresAt = expiresAt,
        decidedBy = if (status == ApprovalStatus.APPROVED) "reviewer-1" else null,
        decidedAt = if (status == ApprovalStatus.APPROVED) now.minusSeconds(20) else null,
        decisionComment = if (status == ApprovalStatus.APPROVED) "approved" else null,
        consumedBy = null,
        consumedAt = null,
        version = version,
    )

    private fun approvedApproval(approvalId: String): ApprovalRequest =
        approval(
            approvalId = approvalId,
            status = ApprovalStatus.APPROVED,
            version = 1L,
        )

    private fun pendingContinuation(
        approvalId: String,
        approvalExpiresAt: Instant = now.plusSeconds(600),
    ): ApprovalContinuation = ApprovalContinuation(
        approvalId = approvalId,
        workflowRunId = "wf-$approvalId",
        correlationId = "corr-$approvalId",
        toolCallId = "tool-call-$approvalId",
        toolName = "claim-payout",
        argumentsDigest = digest("04"),
        policyVersion = "v1",
        workflowDigest = digest("05"),
        status = ApprovalContinuationStatus.PENDING,
        createdAt = now.minusSeconds(30),
        approvalExpiresAt = approvalExpiresAt,
        claimedBy = null,
        claimedAt = null,
        completedAt = null,
        version = 0L,
    )

    private fun digest(suffix: String): Sha256Digest =
        Sha256Digest.of("sha256:${suffix.padStart(64, suffix.first())}")
}

private class ResumeMutableApprovalStore(
    private val approvals: MutableMap<String, ApprovalRequest> = mutableMapOf(),
) : ApprovalStore {
    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        approvals[request.approvalId] = request
        return request
    }

    override suspend fun get(approvalId: String): ApprovalRequest? = approvals[approvalId]

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest {
        throw UnsupportedOperationException("transition not needed in this test")
    }

    override suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: Sha256Digest,
        consumedBy: String,
    ): ApprovalConsumptionReceipt {
        throw UnsupportedOperationException("consumeApprovedOrReplay not needed in this test")
    }
}

private class ResumeMutableApprovalContinuationStore(
    private val continuations: MutableMap<String, ApprovalContinuation> = mutableMapOf(),
) : ApprovalContinuationStore {
    override suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation {
        continuations[continuation.approvalId] = continuation
        return continuation
    }

    override suspend fun get(approvalId: String): ApprovalContinuation? = continuations[approvalId]

    override suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ) = throw UnsupportedOperationException("claimForExecution not needed in this test")

    override suspend fun complete(
        approvalId: String,
        expectedVersion: Long,
        completedBy: String,
    ) = throw UnsupportedOperationException("complete not needed in this test")

    override suspend fun expire(
        approvalId: String,
        expectedVersion: Long,
    ) = throw UnsupportedOperationException("expire not needed in this test")

    override suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ) = throw UnsupportedOperationException("cancel not needed in this test")

    override suspend fun findStaleClaimed(
        claimedBefore: Instant,
        limit: Int,
    ): List<ApprovalContinuation> = emptyList()

    override suspend fun forceCancelClaimed(
        approvalId: String,
        expectedVersion: Long,
        cancelledBy: String,
        reasonCode: String,
    ) = throw UnsupportedOperationException("forceCancelClaimed not needed in this test")

    override suspend fun sweepExpired(): Int = 0
}
