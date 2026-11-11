@file:OptIn(dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi::class)
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
import dev.tramai.core.approval.ApprovalContinuationStore
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
            val adm =
                checkpointStore.admitEffect(
                    workflowName,
                    workflowId,
                    saved.revision,
                    gen,
                    "transfer_funds",
                    APPROVAL_RESUME_EFFECT_KIND,
                )

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
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, adm.sequence),
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

    @Test
    fun `readTimeline detects trailing admission sequence gaps against authoritative checkpoint sequence`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            // Admit 4 effects
            val adm1 = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-1", HTTP_EFFECT_KIND)
            val adm2 = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-2", HTTP_EFFECT_KIND)
            val adm3 = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-3", HTTP_EFFECT_KIND)
            val adm4 = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-4", HTTP_EFFECT_KIND)
            assertThat(adm4.sequence).isEqualTo(4L)

            // Evidence only written for 1 and 2; 3 and 4 were unwritten (crashed before evidence commit)
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
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, adm2.sequence),
                    parent = ParentProvenance.StepAttempt("step-2", "att-2"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1200L,
                    completedAtEpochMillis = 1300L,
                ),
            )

            val timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)

            assertThat(timeline.completeness).isEqualTo(TimelineCompleteness.GAPS_DETECTED)
            assertThat(timeline.admissionGaps).containsExactly(3L, 4L)
            assertThat(timeline.requiresReconciliation).isTrue()
        }
    }

    @Test
    fun `readTimeline detects entirely missing evidence when admissions were committed`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            // Admit 2 effects, but write zero evidence
            checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-1", HTTP_EFFECT_KIND)
            checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-2", HTTP_EFFECT_KIND)

            val timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)

            assertThat(timeline.records).isEmpty()
            assertThat(timeline.completeness).isEqualTo(TimelineCompleteness.GAPS_DETECTED)
            assertThat(timeline.admissionGaps).containsExactly(1L, 2L)
            assertThat(timeline.requiresReconciliation).isTrue()
        }
    }

    @Test
    fun `readTimeline handles custom checkpoint store where authoritative sequence is unknown`() {
        runBlocking {
            // A delegating store that doesn't expose internal currentAdmissionSequence
            val customStore = object : WorkflowCheckpointStore by checkpointStore {}
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, 1L),
                    parent = ParentProvenance.StepAttempt("step-1", "att-1"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1100L,
                ),
            )

            val timeline = WorkflowHistoricalEvidenceReader.readTimeline(customStore, evidenceStore, workflowName, workflowId)

            assertThat(timeline.completeness).isEqualTo(TimelineCompleteness.UNKNOWN)
            assertThat(timeline.requiresReconciliation).isTrue()
        }
    }

    @Test
    fun `readTimeline conservative reconciliation triggers on UNCERTAIN or ADMITTED evidence states`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            val adm = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-1", HTTP_EFFECT_KIND)

            // 1. ADMITTED state
            val record =
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, adm.sequence),
                    parent = ParentProvenance.StepAttempt("step-1", "att-1"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1000L,
                )
            evidenceStore.recordEvidence(record)

            var timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)
            assertThat(timeline.requiresReconciliation).isTrue()

            // 2. DISPATCHED state
            evidenceStore.updateEvidence(record.copy(state = WorkflowEffectEvidenceState.DISPATCHED))
            timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)
            assertThat(timeline.requiresReconciliation).isTrue()

            // 3. UNCERTAIN state
            evidenceStore.updateEvidence(record.copy(state = WorkflowEffectEvidenceState.UNCERTAIN))
            timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)
            assertThat(timeline.requiresReconciliation).isTrue()
            assertThat(timeline.uncertainEffects).hasSize(1)
        }
    }

    @Test
    fun `readTimeline requires reconciliation when E9 record has missing continuation in store or null store`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            val adm = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-1", APPROVAL_RESUME_EFFECT_KIND)
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, adm.sequence),
                    parent = ParentProvenance.ApprovalContinuation("nonexistent-approval", 1L, "tool-x"),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1100L,
                ),
            )

            // Null continuation store -> missing authority -> reconciliation required
            var timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = workflowId,
                    continuationStore = null,
                )
            assertThat(timeline.requiresReconciliation).isTrue()

            // Empty continuation store (approval ID not present) -> missing authority -> reconciliation required
            val emptyContinuationStore = InMemoryApprovalContinuationStore()
            timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = workflowId,
                    continuationStore = emptyContinuationStore,
                )
            assertThat(timeline.requiresReconciliation).isTrue()
        }
    }

    @Test
    fun `readTimeline preserves completed historical effects across multiple checkpoint generations`() {
        runBlocking {
            // 1. Generation 1
            val saved1 = checkpointStore.save(sampleCheckpoint())
            val gen1 = checkNotNull(saved1.checkpointGeneration)
            val adm1 = checkpointStore.admitEffect(workflowName, workflowId, saved1.revision, gen1, "step-1", HTTP_EFFECT_KIND)
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen1, adm1.sequence),
                    parent = ParentProvenance.StepAttempt("step-1", "att-1"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1100L,
                ),
            )

            // 2. Perform genuine generation transition in store
            val rotatedGen = UUID.randomUUID().toString()
            pool.connection.use { conn ->
                val sql =
                    """
                    UPDATE tramai_workflow_checkpoint
                    SET checkpoint_generation = ?
                    WHERE workflow_name = ? AND workflow_id = ?
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, rotatedGen)
                    stmt.setString(2, workflowName)
                    stmt.setString(3, workflowId)
                    stmt.executeUpdate()
                }
            }

            val loaded = checkNotNull(checkpointStore.load(workflowName, workflowId))
            assertThat(loaded.checkpointGeneration).isEqualTo(rotatedGen)

            val adm2 = checkpointStore.admitEffect(workflowName, workflowId, loaded.revision, rotatedGen, "step-2", HTTP_EFFECT_KIND)
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, rotatedGen, adm2.sequence),
                    parent = ParentProvenance.StepAttempt("step-2", "att-2"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 2000L,
                    completedAtEpochMillis = 2100L,
                ),
            )

            val timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)

            assertThat(timeline.records).hasSize(2)
            assertThat(timeline.completedEffects).hasSize(2)
            assertThat(timeline.records[0].key.checkpointGeneration).isEqualTo(gen1)
            assertThat(timeline.records[0].key.admissionSeq).isEqualTo(1L)
            assertThat(timeline.records[1].key.checkpointGeneration).isEqualTo(rotatedGen)
            assertThat(timeline.records[1].key.admissionSeq).isEqualTo(2L)
            assertThat(timeline.completeness).isEqualTo(TimelineCompleteness.COMPLETE)
            assertThat(timeline.admissionGaps).isEmpty()
            assertThat(timeline.requiresReconciliation).isFalse()
        }
    }

    @Test
    fun `readTimeline with committed admissions, zero child evidence, and wrapped store requires reconciliation`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = checkNotNull(saved.checkpointGeneration)

            // Commit admissions directly on the underlying store
            checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-1", HTTP_EFFECT_KIND)
            checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-2", HTTP_EFFECT_KIND)

            // Wrap store so currentAdmissionSequence cannot be derived
            val wrappedStore = object : WorkflowCheckpointStore by checkpointStore {}

            // Zero child evidence written to evidenceStore
            val timeline = WorkflowHistoricalEvidenceReader.readTimeline(wrappedStore, evidenceStore, workflowName, workflowId)

            assertThat(timeline.records).isEmpty()
            assertThat(timeline.completeness).isEqualTo(TimelineCompleteness.UNKNOWN)
            assertThat(timeline.requiresReconciliation).isTrue()
        }
    }

    @Test
    fun `readTimeline flags evidence beyond authoritative counter as integrity violation requiring reconciliation`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = checkNotNull(saved.checkpointGeneration)

            // 1. Authoritative counter is 0, but an evidence record exists with seq 1
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, 1L),
                    parent = ParentProvenance.StepAttempt("step-1", "att-1"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1100L,
                ),
            )

            var timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)
            assertThat(timeline.completeness).isEqualTo(TimelineCompleteness.INTEGRITY_VIOLATION)
            assertThat(timeline.requiresReconciliation).isTrue()

            // 2. Authoritative counter is 1, but evidence has seq 2
            checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-1", HTTP_EFFECT_KIND)
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, 2L),
                    parent = ParentProvenance.StepAttempt("step-2", "att-2"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1200L,
                    completedAtEpochMillis = 1300L,
                ),
            )

            timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)
            assertThat(timeline.completeness).isEqualTo(TimelineCompleteness.INTEGRITY_VIOLATION)
            assertThat(timeline.requiresReconciliation).isTrue()
        }
    }

    @Test
    fun `readTimeline bounds gap enumeration to prevent memory exhaustion on large counters`() {
        runBlocking {
            val sequences = emptyList<Long>()
            val authoritativeMaxSeq = 1_000_000_000L
            val limit = 50

            val result =
                WorkflowHistoricalEvidenceReader.computeAdmissionGaps(
                    sequences = sequences,
                    authoritativeMaxSeq = authoritativeMaxSeq,
                    maxGapLimit = limit,
                )

            assertThat(result.gaps).hasSize(limit)
            assertThat(result.gaps).containsExactlyElementsOf((1L..50L).toList())
            assertThat(result.isTruncated).isTrue()
            assertThat(result.completeness).isEqualTo(TimelineCompleteness.GAPS_DETECTED)
        }
    }

    @Test
    fun `readTimeline exact-limit gap enumeration reports truncation accurately`() {
        runBlocking {
            val limit = 1000

            // 1. Exactly the limit: 1000 missing sequences (1..1000) with sequence 1001 present
            val exactResult =
                WorkflowHistoricalEvidenceReader.computeAdmissionGaps(
                    sequences = listOf(1001L),
                    authoritativeMaxSeq = 1001L,
                    maxGapLimit = limit,
                )
            assertThat(exactResult.gaps).hasSize(1000)
            assertThat(exactResult.isTruncated).isFalse()
            assertThat(exactResult.completeness).isEqualTo(TimelineCompleteness.GAPS_DETECTED)

            // 2. One below the limit: 999 missing sequences (1..999) with sequence 1000 present
            val belowResult =
                WorkflowHistoricalEvidenceReader.computeAdmissionGaps(
                    sequences = listOf(1000L),
                    authoritativeMaxSeq = 1000L,
                    maxGapLimit = limit,
                )
            assertThat(belowResult.gaps).hasSize(999)
            assertThat(belowResult.isTruncated).isFalse()
            assertThat(belowResult.completeness).isEqualTo(TimelineCompleteness.GAPS_DETECTED)

            // 3. One above the limit: 1001 missing sequences (1..1000 and 1002) with sequence 1001 present
            val aboveResult =
                WorkflowHistoricalEvidenceReader.computeAdmissionGaps(
                    sequences = listOf(1001L),
                    authoritativeMaxSeq = 1002L,
                    maxGapLimit = limit,
                )
            assertThat(aboveResult.gaps).hasSize(1000)
            assertThat(aboveResult.isTruncated).isTrue()
            assertThat(aboveResult.completeness).isEqualTo(TimelineCompleteness.GAPS_DETECTED)
        }
    }

    @Test
    fun `readTimeline rejects completed E9 evidence when continuation identity mismatches run, tool, or version lineage`() {
        runBlocking {
            val continuationStore = InMemoryApprovalContinuationStore()

            // Discriminator 1: Mismatched tool name ONLY (run and claim version lineage match)
            val toolRunId = "e9-tool-mismatch"
            val toolClaimVer = setupContinuation(continuationStore, "app-tool-1", toolRunId, "transfer_funds")
            val toolSaved = checkpointStore.save(sampleCheckpoint().copy(workflowId = toolRunId))
            val toolGen = checkNotNull(toolSaved.checkpointGeneration)
            val toolAdm =
                checkpointStore.admitEffect(
                    workflowName,
                    toolRunId,
                    toolSaved.revision,
                    toolGen,
                    "unauthorized_tool",
                    APPROVAL_RESUME_EFFECT_KIND,
                )
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, toolRunId, toolGen, toolAdm.sequence),
                    parent =
                        ParentProvenance.ApprovalContinuation(
                            "app-tool-1",
                            toolClaimVer,
                            "unauthorized_tool",
                        ),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1200L,
                ),
            )
            var timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = toolRunId,
                    continuationStore = continuationStore,
                )
            assertThat(timeline.requiresReconciliation).isTrue()

            // Discriminator 2A: Version lineage mismatch ONLY — Equal version (completed.version == parent.continuationVersion)
            // Completed continuation is at version 2; evidence parent claims version 2 (rejected: must be 2 == 1 + 1)
            val verEqualRunId = "e9-ver-equal"
            val verEqualClaimVer = setupContinuation(continuationStore, "app-ver-eq", verEqualRunId, "transfer_funds")
            val verEqSaved = checkpointStore.save(sampleCheckpoint().copy(workflowId = verEqualRunId))
            val verEqGen = checkNotNull(verEqSaved.checkpointGeneration)
            val verEqAdm =
                checkpointStore.admitEffect(
                    workflowName,
                    verEqualRunId,
                    verEqSaved.revision,
                    verEqGen,
                    "transfer_funds",
                    APPROVAL_RESUME_EFFECT_KIND,
                )
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, verEqualRunId, verEqGen, verEqAdm.sequence),
                    parent =
                        ParentProvenance.ApprovalContinuation(
                            "app-ver-eq",
                            verEqualClaimVer + 1L,
                            "transfer_funds",
                        ),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1200L,
                ),
            )
            timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = verEqualRunId,
                    continuationStore = continuationStore,
                )
            assertThat(timeline.requiresReconciliation).isTrue()

            // Discriminator 2B: Version lineage mismatch ONLY — Arbitrary version
            val versionRunId = "e9-ver-arbitrary"
            setupContinuation(continuationStore, "app-ver-arb", versionRunId, "transfer_funds")
            val verSaved = checkpointStore.save(sampleCheckpoint().copy(workflowId = versionRunId))
            val verGen = checkNotNull(verSaved.checkpointGeneration)
            val verAdm =
                checkpointStore.admitEffect(
                    workflowName,
                    versionRunId,
                    verSaved.revision,
                    verGen,
                    "transfer_funds",
                    APPROVAL_RESUME_EFFECT_KIND,
                )
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, versionRunId, verGen, verAdm.sequence),
                    parent =
                        ParentProvenance.ApprovalContinuation(
                            "app-ver-arb",
                            999L,
                            "transfer_funds",
                        ),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1200L,
                ),
            )
            timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = versionRunId,
                    continuationStore = continuationStore,
                )
            assertThat(timeline.requiresReconciliation).isTrue()

            // Discriminator 3: Mismatched workflow run ID ONLY (tool and version lineage match)
            val originalRunId = "e9-run-original"
            val impostorRunId = "e9-run-impostor"
            val origClaimVer = setupContinuation(continuationStore, "app-run-mismatch", originalRunId, "transfer_funds")
            val impSaved = checkpointStore.save(sampleCheckpoint().copy(workflowId = impostorRunId))
            val impGen = checkNotNull(impSaved.checkpointGeneration)
            val impAdm =
                checkpointStore.admitEffect(
                    workflowName,
                    impostorRunId,
                    impSaved.revision,
                    impGen,
                    "transfer_funds",
                    APPROVAL_RESUME_EFFECT_KIND,
                )
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, impostorRunId, impGen, impAdm.sequence),
                    parent =
                        ParentProvenance.ApprovalContinuation(
                            "app-run-mismatch",
                            origClaimVer,
                            "transfer_funds",
                        ),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1200L,
                ),
            )
            timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = impostorRunId,
                    continuationStore = continuationStore,
                )
            assertThat(timeline.requiresReconciliation).isTrue()

            // Matching: legitimate completed record with matching tool, run ID, and incremented version lineage
            val validRunId = "e9-valid-run"
            val validClaimVer = setupContinuation(continuationStore, "app-valid-1", validRunId, "transfer_funds")
            val validSaved = checkpointStore.save(sampleCheckpoint().copy(workflowId = validRunId))
            val validGen = checkNotNull(validSaved.checkpointGeneration)
            val validAdm =
                checkpointStore.admitEffect(
                    workflowName,
                    validRunId,
                    validSaved.revision,
                    validGen,
                    "transfer_funds",
                    APPROVAL_RESUME_EFFECT_KIND,
                )
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, validRunId, validGen, validAdm.sequence),
                    parent =
                        ParentProvenance.ApprovalContinuation(
                            "app-valid-1",
                            validClaimVer,
                            "transfer_funds",
                        ),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1200L,
                ),
            )
            timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = validRunId,
                    continuationStore = continuationStore,
                )
            assertThat(timeline.requiresReconciliation).isFalse()
        }
    }

    @Test
    fun `readTimeline with ReadOnlyApprovalContinuationLookup preserves expired pending continuation without mutation`() {
        runBlocking {
            // Exercises the ReadOnlyApprovalContinuationLookup interface contract proving that a non-mutating
            // adapter allows readTimeline to inspect continuation metadata and trigger reconciliation without
            // mutating the underlying stored status or version.
            // Note: Native non-mutating peek against durable JdbcApprovalContinuationStore without lazy expiry
            // transitions requires cross-module SPI changes and is classified as PARTIAL / deferred.
            val runId = "read-only-exp-test"
            val now = Instant.now()
            val sensitiveArgs = SensitiveToolArguments.of("{}")
            val expiredPending =
                ApprovalContinuation(
                    approvalId = "app-expired-pending",
                    workflowRunId = runId,
                    correlationId = "corr-exp",
                    toolCallId = "call-exp",
                    toolName = "transfer_funds",
                    argumentsDigest = digester.digest(sensitiveArgs),
                    policyVersion = "p1",
                    workflowDigest =
                        dev.tramai.core.approval.Sha256Digest
                            .of("sha256:" + "0".repeat(64)),
                    status = ApprovalContinuationStatus.PENDING,
                    createdAt = now.minusSeconds(120),
                    approvalExpiresAt = now.minusSeconds(60),
                    claimedBy = null,
                    claimedAt = null,
                    completedAt = null,
                    version = 0L,
                )

            val durableState = mutableMapOf("app-expired-pending" to expiredPending)
            val readOnlyLookup = ReadOnlyApprovalContinuationLookup { approvalId -> durableState[approvalId] }

            val saved = checkpointStore.save(sampleCheckpoint().copy(workflowId = runId))
            val gen = checkNotNull(saved.checkpointGeneration)
            val adm =
                checkpointStore.admitEffect(
                    workflowName,
                    runId,
                    saved.revision,
                    gen,
                    "transfer_funds",
                    APPROVAL_RESUME_EFFECT_KIND,
                )
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, runId, gen, adm.sequence),
                    parent =
                        ParentProvenance.ApprovalContinuation(
                            "app-expired-pending",
                            1L,
                            "transfer_funds",
                        ),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1200L,
                ),
            )

            val timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = runId,
                    continuationLookup = readOnlyLookup,
                )

            // PENDING continuation is not completed -> reconciliation required
            assertThat(timeline.requiresReconciliation).isTrue()

            // Crucial assertion: the stored continuation was NOT lazily mutated to EXPIRED or version incremented!
            val storedAfter = checkNotNull(durableState["app-expired-pending"])
            assertThat(storedAfter.status).isEqualTo(ApprovalContinuationStatus.PENDING)
            assertThat(storedAfter.version).isEqualTo(0L)
        }
    }

    private suspend fun setupContinuation(
        continuationStore: ApprovalContinuationStore,
        approvalId: String,
        workflowRunId: String,
        toolName: String,
    ): Long {
        val sensitiveArgs = SensitiveToolArguments.of("{}")
        val continuation =
            ApprovalContinuation(
                approvalId = approvalId,
                workflowRunId = workflowRunId,
                correlationId = "corr-$approvalId",
                toolCallId = "call-$approvalId",
                toolName = toolName,
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
        val claimed = continuationStore.claimForExecution(approvalId, 0L, "worker-1")
        continuationStore.complete(approvalId, claimed.continuation.version, "worker-1")
        return claimed.continuation.version
    }
}
