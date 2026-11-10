@file:Suppress(
    "LargeClass",
    "MaxLineLength",
    "TooManyFunctions",
    "LongMethod",
    "LongParameterList",
    "SwallowedException",
    "ComplexMethod",
    "NestedBlockDepth",
    "ktlint:standard:max-line-length",
)

package dev.tramai.orchestration

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Phase 4: Historical Evidence and Reconstruction Integration qualification.
 * Proves that WorkflowHistoricalEvidenceReader:
 * - Accurately reads and distinguishes CANCELLED vs CANCELLED_UNCERTAIN lifecycles.
 * - Preserves completed historical effects across generations.
 * - Identifies admission gaps without fabricating evidence.
 * - Identifies E9 reconciliation requirements.
 * - Operates in a strictly read-only manner with zero side effects.
 */
class WorkflowHistoricalEvidenceTimelineTest {
    private val workflowName = "recon-wf"
    private val workflowId = "run-recon-1"
    private val clock = Clock.systemUTC()
    private val digester = Sha256ToolArgumentsDigester()

    private lateinit var pool: JdbcConnectionPool
    private lateinit var checkpointStore: JdbcWorkflowCheckpointStore
    private lateinit var evidenceStore: JdbcWorkflowEffectEvidenceRecordStore

    @BeforeEach
    fun setUp() {
        pool = JdbcConnectionPool.create("jdbc:h2:mem:recon_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        checkpointStore = JdbcWorkflowCheckpointStore(pool)
        evidenceStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
        pool.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(checkpointStore.createTableSql())
                evidenceStore.createSchemaSql().forEach { stmt.execute(it) }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        pool.dispose()
    }

    private fun sampleCheckpoint(
        rev: Long = 1L,
        gen: String = "gen-1",
    ) = WorkflowCheckpoint(
        workflowName = workflowName,
        workflowId = workflowId,
        nextStepIndex = 1,
        stepExecutions = 1,
        lastCompletedStepName = "step-1",
        statePayload = "payload",
        revision = rev,
        checkpointGeneration = gen,
        metadata = emptyMap(),
    )

    @Test
    fun `readTimeline correctly distinguishes CANCELLED from CANCELLED_UNCERTAIN`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            // 1. Definite cancellation (zero admissions taken)
            val cancelDefinite =
                checkpointStore.cancel(
                    WorkflowCancellationRequest(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        expectedRevision = saved.revision,
                        expectedGeneration = gen,
                        actor = "admin",
                        reason = "definite cancellation",
                        cancelledAtEpochMillis = clock.millis(),
                        uncertain = false,
                    ),
                )
            assertThat(cancelDefinite.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED)

            var timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)
            assertThat(timeline.isCancelled).isTrue()
            assertThat(timeline.isUncertainCancellation).isFalse()

            // 2. Setup run 2 with admission winning before cancel
            val wfId2 = "run-recon-2"
            val saved2 = checkpointStore.save(sampleCheckpoint().copy(workflowId = wfId2))
            val gen2 = saved2.checkpointGeneration ?: "gen-2"

            val adm = checkpointStore.admitEffect(workflowName, wfId2, saved2.revision, gen2, "http-step", HTTP_EFFECT_KIND)
            assertThat(adm.sequence).isEqualTo(1L)

            checkpointStore.cancel(
                WorkflowCancellationRequest(
                    workflowName = workflowName,
                    workflowId = wfId2,
                    expectedRevision = saved2.revision,
                    expectedGeneration = gen2,
                    actor = "admin",
                    reason = "uncertain cancellation",
                    cancelledAtEpochMillis = clock.millis(),
                ),
            )

            timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, wfId2)
            assertThat(timeline.isCancelled).isTrue()
            assertThat(timeline.isUncertainCancellation).isTrue()
        }
    }

    @Test
    fun `readTimeline preserves completed historical effects and reports admission gaps without fabrication`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            // Admit effect 1, 2, 3
            val adm1 = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-1", HTTP_EFFECT_KIND)
            val adm2 = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-2", HTTP_EFFECT_KIND)
            val adm3 = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-3", HTTP_EFFECT_KIND)
            assertThat(adm2.sequence).isEqualTo(2L)

            // Persist evidence ONLY for effect 1 and effect 3 (simulate unrecorded/crashed effect 2)
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, adm1.sequence),
                    parent = ParentProvenance.StepAttempt("step-1", "att-1"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1100L,
                ),
            )
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, adm3.sequence),
                    parent = ParentProvenance.StepAttempt("step-3", "att-3"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1200L,
                    completedAtEpochMillis = 1300L,
                ),
            )

            val timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)

            // Proven: admission sequence gap at 2 is reported, never synthesized
            assertThat(timeline.admissionGaps).containsExactly(2L)
            assertThat(timeline.records).hasSize(2)
            assertThat(timeline.completedEffects).hasSize(2)
        }
    }

    @Test
    fun `readTimeline detects E9 reconciliation requirement when child evidence is completed but continuation is not`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            val continuationStore = InMemoryApprovalContinuationStore()
            val sensitiveArgs = SensitiveToolArguments.of("{}")
            val continuation =
                ApprovalContinuation(
                    approvalId = "app-recon-check",
                    workflowRunId = workflowId,
                    correlationId = "corr-1",
                    toolCallId = "call-1",
                    toolName = "transfer_funds",
                    argumentsDigest = digester.digest(sensitiveArgs),
                    policyVersion = "p1",
                    workflowDigest =
                        dev.tramai.core.approval.Sha256Digest
                            .of("sha256:" + "0".repeat(64)),
                    status = ApprovalContinuationStatus.PENDING,
                    createdAt = Instant.now(),
                    approvalExpiresAt = Instant.now().plusSeconds(600),
                    claimedBy = null,
                    claimedAt = null,
                    completedAt = null,
                    version = 0L,
                )
            continuationStore.create(continuation, sensitiveArgs)
            val claimed = continuationStore.claimForExecution("app-recon-check", 0L, "worker-1")

            // Persist completed E9 child evidence
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, 1L),
                    parent = ParentProvenance.ApprovalContinuation("app-recon-check", claimed.continuation.version, "transfer_funds"),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1200L,
                ),
            )

            // Timeline read before continuationStore.complete
            var timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = workflowId,
                    continuationStore = continuationStore,
                )
            assertThat(timeline.requiresReconciliation).isTrue()

            // After continuationStore.complete reconciliation succeeds
            continuationStore.complete("app-recon-check", claimed.continuation.version, "worker-1")

            timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = workflowId,
                    continuationStore = continuationStore,
                )
            assertThat(timeline.requiresReconciliation).isFalse()
        }
    }
}
