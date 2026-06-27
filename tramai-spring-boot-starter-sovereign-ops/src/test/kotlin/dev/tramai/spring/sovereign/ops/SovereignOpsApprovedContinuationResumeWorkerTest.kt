package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialStore
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.SealedResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Contract tests for [SovereignOpsApprovedContinuationResumeWorker].
 *
 * Uses in-memory fakes for the queue, credential store, and control plane
 * to verify the worker's decision logic without a database.
 */
class SovereignOpsApprovedContinuationResumeWorkerTest {

    private lateinit var queue: FakeApprovedContinuationResumeQueue
    private lateinit var credentialStore: FakeApprovalResumeCredentialStore
    private lateinit var controlPlane: FakeApprovalResumeControlPlane
    private lateinit var worker: SovereignOpsApprovedContinuationResumeWorker

    @BeforeEach
    fun setUp() {
        queue = FakeApprovedContinuationResumeQueue()
        credentialStore = FakeApprovalResumeCredentialStore()
        controlPlane = FakeApprovalResumeControlPlane()
        worker = SovereignOpsApprovedContinuationResumeWorker(
            queue = queue,
            credentialStore = credentialStore,
            resumeControlPlane = controlPlane,
            workerId = "test-worker",
            leaseDuration = Duration.ofMinutes(2),
            retryDelay = Duration.ofSeconds(30),
            conflictRetryDelay = Duration.ofSeconds(60),
            clock = Clock.systemUTC(),
        )
    }

    @Test
    fun `no items to process returns zero counts`() {
        runBlocking {
            val result = worker.runOnce(limit = 10)
            assertThat(result.scanned).isZero()
            assertThat(result.resumed).isZero()
            assertThat(result.skipped).isZero()
            assertThat(result.failed).isZero()
        }
    }

    @Test
    fun `approved pending continuation resumes once`() {
        runBlocking {
            val approvalId = ApprovalId("test-001")
            queue.addItem(approvalId, approvalVersion = 1, continuationVersion = 1)
            credentialStore.add(credential(approvalId, ResumeToken("tok-001")))
            controlPlane.result = ApprovalResumeResult.Resumed(
                approvalId = approvalId,
                resumedBy = "tramai-sovereign-approved-resume-worker",
                result = "workflow-result",
            )

            val result = worker.runOnce(limit = 10)

            assertThat(result.scanned).isEqualTo(1)
            assertThat(result.resumed).isEqualTo(1)
            assertThat(result.skipped).isZero()
            assertThat(result.failed).isZero()
            assertThat(credentialStore.deleted).contains(approvalId)
            assertThat(queue.markedSucceeded).contains(approvalId)
        }
    }

    @Test
    fun `missing credential skips the item`() {
        runBlocking {
            val approvalId = ApprovalId("test-002")
            queue.addItem(approvalId, approvalVersion = 1, continuationVersion = 1)
            // No credential in store

            val result = worker.runOnce(limit = 10)

            assertThat(result.scanned).isEqualTo(1)
            assertThat(result.resumed).isZero()
            assertThat(result.skipped).isEqualTo(1)
            assertThat(result.failed).isZero()
            assertThat(queue.markedFailed).containsKey(approvalId)
            assertThat(queue.markedFailed[approvalId]).isEqualTo("credential-not-found")
        }
    }

    @Test
    fun `already completed reconciles and deletes credential`() {
        runBlocking {
            val approvalId = ApprovalId("test-003")
            queue.addItem(approvalId, approvalVersion = 1, continuationVersion = 1)
            credentialStore.add(credential(approvalId, ResumeToken("tok-003")))
            controlPlane.result = ApprovalResumeResult.AlreadyCompleted(approvalId)

            val result = worker.runOnce(limit = 10)

            assertThat(result.resumed).isEqualTo(1)
            assertThat(credentialStore.deleted).contains(approvalId)
            assertThat(queue.markedSucceeded).contains(approvalId)
        }
    }

    @Test
    fun `not approved skips item and keeps credential`() {
        runBlocking {
            val approvalId = ApprovalId("test-004")
            queue.addItem(approvalId, approvalVersion = 1, continuationVersion = 1)
            credentialStore.add(credential(approvalId, ResumeToken("tok-004")))
            controlPlane.result = ApprovalResumeResult.NotApproved(
                approvalId = approvalId,
                status = ApprovalStatus.DENIED,
            )

            val result = worker.runOnce(limit = 10)

            assertThat(result.skipped).isEqualTo(1)
            assertThat(result.resumed).isZero()
            assertThat(credentialStore.deleted).doesNotContain(approvalId)
        }
    }

    @Test
    fun `resume success deletes credential`() {
        runBlocking {
            val approvalId = ApprovalId("test-005")
            queue.addItem(approvalId, approvalVersion = 1, continuationVersion = 1)
            credentialStore.add(credential(approvalId, ResumeToken("tok-005")))
            controlPlane.result = ApprovalResumeResult.Resumed(
                approvalId = approvalId,
                resumedBy = "tramai-sovereign-approved-resume-worker",
                result = "done",
            )

            val result = worker.runOnce(limit = 10)

            assertThat(result.resumed).isEqualTo(1)
            assertThat(credentialStore.deleted).contains(approvalId)
        }
    }

    @Test
    fun `continuation expired deletes credential`() {
        runBlocking {
            val approvalId = ApprovalId("test-006")
            queue.addItem(approvalId, approvalVersion = 1, continuationVersion = 1)
            credentialStore.add(credential(approvalId, ResumeToken("tok-006")))
            controlPlane.result = ApprovalResumeResult.Conflict(
                approvalId = approvalId,
                reason = "approval-continuation-expired",
            )

            val result = worker.runOnce(limit = 10)

            assertThat(result.skipped).isEqualTo(1)
            assertThat(result.resumed).isZero()
            assertThat(result.failed).isZero()
            assertThat(credentialStore.deleted).contains(approvalId)
        }
    }

    @Test
    fun `resume failed keeps credential for retry`() {
        runBlocking {
            val approvalId = ApprovalId("test-007")
            queue.addItem(approvalId, approvalVersion = 1, continuationVersion = 1)
            credentialStore.add(credential(approvalId, ResumeToken("tok-007")))
            controlPlane.result = ApprovalResumeResult.Failed(
                approvalId = approvalId,
                reason = "engine-timeout",
            )

            val result = worker.runOnce(limit = 10)

            assertThat(result.failed).isEqualTo(1)
            assertThat(result.resumed).isZero()
            assertThat(credentialStore.deleted).doesNotContain(approvalId)
            assertThat(queue.markedFailed).containsKey(approvalId)
        }
    }

    // ── Fakes ───────────────────────────────────────────────────────

    private fun credential(approvalId: ApprovalId, token: ResumeToken): ApprovalResumeCredentialRecord {
        val now = Instant.parse("2026-06-01T12:00:00Z")
        return ApprovalResumeCredentialRecord(
            approvalId = approvalId,
            workflowRunId = WorkflowRunId("wf-${approvalId.value}"),
            resumeToken = SealedResumeToken.seal(token),
            createdAt = now,
            expiresAt = now.plusSeconds(300),
            version = 1L,
        )
    }

    private class FakeApprovedContinuationResumeQueue : ApprovedContinuationResumeQueue {
        val items = mutableListOf<ApprovedContinuationResumeWorkItem>()
        val markedSucceeded = mutableListOf<ApprovalId>()
        val markedFailed = mutableMapOf<ApprovalId, String>()

        fun addItem(approvalId: ApprovalId, approvalVersion: Long, continuationVersion: Long) {
            items.add(
                ApprovedContinuationResumeWorkItem(
                    approvalId = approvalId,
                    approvalVersion = approvalVersion,
                    continuationVersion = continuationVersion,
                    workflowRunId = "wf-${approvalId.value}",
                ),
            )
        }

        override suspend fun claimApprovedPending(
            workerId: String,
            limit: Int,
            leaseUntil: Instant,
        ): List<ApprovedContinuationResumeWorkItem> {
            val result = items.take(limit).toList()
            items.clear()
            return result
        }

        override suspend fun markResumeSucceeded(approvalId: ApprovalId, workerId: String) {
            markedSucceeded.add(approvalId)
        }

        override suspend fun markResumeFailed(
            approvalId: ApprovalId,
            workerId: String,
            reasonCode: String,
            retryAt: Instant?,
        ) {
            markedFailed[approvalId] = reasonCode
        }
    }

    private class FakeApprovalResumeCredentialStore : ApprovalResumeCredentialStore {
        val records = mutableMapOf<ApprovalId, ApprovalResumeCredentialRecord>()
        val deleted = mutableListOf<ApprovalId>()

        fun add(record: ApprovalResumeCredentialRecord) {
            records[record.approvalId] = record
        }

        override suspend fun create(record: ApprovalResumeCredentialRecord) {
            records[record.approvalId] = record
        }

        override suspend fun get(approvalId: ApprovalId): ApprovalResumeCredentialRecord? =
            records[approvalId]

        override suspend fun delete(approvalId: ApprovalId) {
            records.remove(approvalId)
            deleted.add(approvalId)
        }
    }

    private class FakeApprovalResumeControlPlane : ApprovalResumeControlPlane {
        var result: ApprovalResumeResult = ApprovalResumeResult.Resumed(
            approvalId = ApprovalId("default"),
            resumedBy = "test",
            result = null,
        )

        override suspend fun resume(command: ApprovalResumeCommand): ApprovalResumeResult = result
    }
}
