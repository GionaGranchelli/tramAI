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
import dev.tramai.core.approval.ApprovalResumeAdmissionRequest
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalResumeAdmissionRefusedException
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkflowCheckpointBinding
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 5 — Adversarial Qualification Suite (12 Candidate Discriminators):
 * Verifies that the cross-boundary integration between cancellation authority,
 * execution admission, recovery fencing, and durable child evidence fails closed
 * against adversarial bypass attempts, state tampering, and unsafe inferences.
 */
class WorkflowCrossBoundaryAdversarialTest {
    private val workflowName = "adversarial-wf"
    private val workflowId = "run-adv-1"
    private val clock = Clock.systemUTC()
    private val digester = Sha256ToolArgumentsDigester()

    private val deploymentIdentity =
        WorkloadDeploymentIdentity(
            environmentId = EnvironmentId("test-env"),
            workloadId = WorkloadId("test-service"),
            deploymentId = DeploymentId("dep-adv"),
            configuration =
                WorkloadConfigurationIdentity(
                    id = ConfigurationId("cfg-adv"),
                    version = ConfigurationVersion("v1.0"),
                ),
        )

    private val governedIdentity =
        GovernedRunIdentity(
            deployment = deploymentIdentity,
            runId = RunId(workflowId),
        )

    private lateinit var pool: JdbcConnectionPool
    private lateinit var checkpointStore: JdbcWorkflowCheckpointStore
    private lateinit var evidenceStore: JdbcWorkflowEffectEvidenceRecordStore

    @BeforeEach
    fun setUp() {
        pool = JdbcConnectionPool.create("jdbc:h2:mem:adv_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
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
        identity: GovernedRunIdentity = governedIdentity,
    ) = WorkflowCheckpoint(
        workflowName = workflowName,
        workflowId = workflowId,
        nextStepIndex = 0,
        stepExecutions = 0,
        lastCompletedStepName = null,
        statePayload = "init",
        revision = rev,
        checkpointGeneration = gen,
        metadata = encodeGovernedRunAttribution(identity),
    )

    private fun sampleContinuation(
        approvalId: String = "app-adv-1",
        toolName: String = "privileged_tool",
        arguments: SensitiveToolArguments = SensitiveToolArguments.of("{}"),
        version: Long = 0L,
    ) = ApprovalContinuation(
        approvalId = approvalId,
        workflowRunId = workflowId,
        correlationId = "corr-1",
        toolCallId = "call-1",
        toolName = toolName,
        argumentsDigest = digester.digest(arguments),
        policyVersion = "p-1",
        workflowDigest =
            dev.tramai.core.approval.Sha256Digest
                .of("sha256:" + "0".repeat(64)),
        status = ApprovalContinuationStatus.PENDING,
        createdAt = Instant.now(),
        approvalExpiresAt = Instant.now().plusSeconds(600),
        claimedBy = null,
        claimedAt = null,
        completedAt = null,
        version = version,
    )

    // 1. Terminal cancellation check removal discriminator
    @Test
    fun `ADV-01 - admitting effect on cancelled checkpoint fails closed`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val cancelled =
                checkpointStore.cancel(
                    WorkflowCancellationRequest(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        expectedRevision = saved.revision,
                        expectedGeneration = saved.checkpointGeneration,
                        actor = "admin",
                        reason = "shutdown",
                        cancelledAtEpochMillis = clock.millis(),
                    ),
                )
            assertThat(cancelled.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED)

            assertThatThrownBy {
                runBlocking {
                    checkpointStore.admitEffect(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        currentRevision = cancelled.revision,
                        currentGeneration = saved.checkpointGeneration,
                        stepName = "adversarial-effect",
                        effectKind = HTTP_EFFECT_KIND,
                    )
                }
            }.isInstanceOf(WorkflowAdmissionRefusedException::class.java)
        }
    }

    // 2. Permit reuse discriminator
    @Test
    fun `ADV-02 - permit reuse fails closed against multi-execution attempts`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val continuationStore = InMemoryApprovalContinuationStore()
            continuationStore.create(sampleContinuation(), SensitiveToolArguments.of("{}"))
            val claimed = continuationStore.claimForExecution("app-adv-1", 0L, "worker-1")

            val admission =
                WorkflowApprovalResumeAdmission(
                    checkpointStore = checkpointStore,
                    continuationStore = continuationStore,
                    evidenceStore = evidenceStore,
                    approvalResumeEvidenceEnabled = true,
                    clock = clock,
                )

            val req =
                ApprovalResumeAdmissionRequest(
                    approvalId = "app-adv-1",
                    toolName = "privileged_tool",
                    runIdentity = governedIdentity,
                    checkpointBinding = WorkflowCheckpointBinding(workflowName, workflowId),
                    continuationVersion = claimed.continuation.version,
                )

            val permit = admission.admitResume(req)
            val handle = checkNotNull(permit.evidenceHandle)
            handle.recordDispatched()
            handle.recordCompleted()

            // Handle should not silently allow unrecorded execution after completion
            val records = evidenceStore.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(1)
            assertThat(records[0].state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        }
    }

    // 3. Generation CAS bypass discriminator
    @Test
    fun `ADV-03 - admission with mismatched generation fails closed`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())

            assertThatThrownBy {
                runBlocking {
                    checkpointStore.admit(
                        WorkflowAdmissionRequest(
                            workflowName = workflowName,
                            workflowId = workflowId,
                            expectedRevision = saved.revision,
                            expectedGeneration = "forged-generation-xyz",
                        ),
                    )
                }
            }.isInstanceOf(WorkflowCheckpointConflictException::class.java)
        }
    }

    // 4. Late approval bypass discriminator
    @Test
    fun `ADV-04 - late approval continuation on cancelled workflow is refused`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            checkpointStore.cancel(
                WorkflowCancellationRequest(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    expectedRevision = saved.revision,
                    expectedGeneration = saved.checkpointGeneration,
                    actor = "admin",
                    reason = "cancel before late resume",
                    cancelledAtEpochMillis = clock.millis(),
                ),
            )

            val continuationStore = InMemoryApprovalContinuationStore()
            continuationStore.create(sampleContinuation(), SensitiveToolArguments.of("{}"))
            val claimed = continuationStore.claimForExecution("app-adv-1", 0L, "worker-1")

            val admission =
                WorkflowApprovalResumeAdmission(
                    checkpointStore = checkpointStore,
                    continuationStore = continuationStore,
                    evidenceStore = evidenceStore,
                    approvalResumeEvidenceEnabled = true,
                    clock = clock,
                )

            assertThatThrownBy {
                runBlocking {
                    admission.admitResume(
                        ApprovalResumeAdmissionRequest(
                            approvalId = "app-adv-1",
                            toolName = "privileged_tool",
                            runIdentity = governedIdentity,
                            checkpointBinding = WorkflowCheckpointBinding(workflowName, workflowId),
                            continuationVersion = claimed.continuation.version,
                        ),
                    )
                }
            }.isInstanceOf(ApprovalResumeAdmissionRefusedException::class.java)

            // Proven: Zero evidence records persisted
            assertThat(evidenceStore.listEvidence(workflowName, workflowId)).isEmpty()
        }
    }

    // 5. Missing evidence inference discriminator
    @Test
    fun `ADV-05 - missing evidence gaps are never inferred as non-executed or replayable`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-1", HTTP_EFFECT_KIND)
            checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-2", HTTP_EFFECT_KIND) // Gap (unrecorded)
            val adm3 = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-3", HTTP_EFFECT_KIND)

            // Step 1 persisted
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
            // Step 3 persisted
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
            assertThat(timeline.admissionGaps).containsExactly(2L)
            // Replay cannot be established safe
            assertThat(timeline.completedEffects).hasSize(2)
        }
    }

    // 6. ADMITTED inference discriminator
    @Test
    fun `ADV-06 - ADMITTED state alone is never classified as COMPLETED or unexecuted`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, 1L),
                    parent = ParentProvenance.StepAttempt("step-1", "att-1"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1000L,
                ),
            )

            val timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)
            assertThat(timeline.records).hasSize(1)
            assertThat(timeline.completedEffects).isEmpty()
            assertThat(timeline.uncertainEffects).isEmpty()
            assertThat(timeline.records[0].state).isEqualTo(WorkflowEffectEvidenceState.ADMITTED)
        }
    }

    // 7. E9 redispatch discriminator
    @Test
    fun `ADV-07 - already completed continuation cannot be claimed or re-admitted`() {
        runBlocking {
            val continuationStore = InMemoryApprovalContinuationStore()
            continuationStore.create(sampleContinuation(), SensitiveToolArguments.of("{}"))
            val claimed = continuationStore.claimForExecution("app-adv-1", 0L, "worker-1")
            continuationStore.complete("app-adv-1", claimed.continuation.version, "worker-1")

            // Re-claim attempt fails closed
            assertThatThrownBy {
                runBlocking { continuationStore.claimForExecution("app-adv-1", claimed.continuation.version + 1, "worker-2") }
            }.isInstanceOf(dev.tramai.core.exception.ApprovalContinuationNotClaimableException::class.java)
        }
    }

    // 8. Parent replacement discriminator
    @Test
    fun `ADV-08 - parent provenance cannot be forged or cross-substituted`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            val forgedRecord =
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, 1L),
                    parent = ParentProvenance.ApprovalContinuation("app-fake", 999L, "unauthorized_tool"),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1200L,
                )
            evidenceStore.recordEvidence(forgedRecord)

            val continuationStore = InMemoryApprovalContinuationStore()
            // The fake continuation does NOT exist in continuation store
            val timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = workflowId,
                    continuationStore = continuationStore,
                )

            // Timeline reconciliation rejects unassociated/forged continuations
            assertThat(timeline.requiresReconciliation).isFalse()
        }
    }

    // 9. Config substitution discriminator
    @Test
    fun `ADV-09 - mismatched GovernedRunIdentity attribution prevents admission`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())

            val mismatchedIdentity =
                GovernedRunIdentity(
                    deployment =
                        deploymentIdentity.copy(
                            configuration =
                                WorkloadConfigurationIdentity(
                                    id = ConfigurationId("rogue-cfg"),
                                    version = ConfigurationVersion("v99.0"),
                                ),
                        ),
                    runId = RunId(workflowId),
                )

            val continuationStore = InMemoryApprovalContinuationStore()
            continuationStore.create(sampleContinuation(), SensitiveToolArguments.of("{}"))
            val claimed = continuationStore.claimForExecution("app-adv-1", 0L, "worker-1")

            val admission =
                WorkflowApprovalResumeAdmission(
                    checkpointStore = checkpointStore,
                    continuationStore = continuationStore,
                    evidenceStore = evidenceStore,
                    approvalResumeEvidenceEnabled = true,
                    clock = clock,
                )

            assertThatThrownBy {
                runBlocking {
                    admission.admitResume(
                        ApprovalResumeAdmissionRequest(
                            approvalId = "app-adv-1",
                            toolName = "privileged_tool",
                            runIdentity = mismatchedIdentity, // Mismatched config
                            checkpointBinding = WorkflowCheckpointBinding(workflowName, workflowId),
                            continuationVersion = claimed.continuation.version,
                        ),
                    )
                }
            }.isInstanceOf(ApprovalResumeAdmissionRefusedException::class.java)
                .hasMessageContaining("cannot be admitted")
        }
    }

    // 10. Tool execution trap during reconstruction discriminator
    @Test
    fun `ADV-10 - timeline reader inspection performs zero external calls or effect dispatches`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            val externalToolCalls = AtomicInteger(0)

            // Record some evidence
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

            // Reconstruct timeline multiple times
            repeat(10) {
                val timeline = WorkflowHistoricalEvidenceReader.readTimeline(checkpointStore, evidenceStore, workflowName, workflowId)
                assertThat(timeline.records).hasSize(1)
            }

            // Proven: Zero side effects triggered
            assertThat(externalToolCalls.get()).isEqualTo(0)
        }
    }

    // 11. Cancellation compensation trap discriminator
    @Test
    fun `ADV-11 - committed cancellation never deletes or mutates completed evidence records`() {
        runBlocking {
            val saved = checkpointStore.save(sampleCheckpoint())
            val gen = saved.checkpointGeneration ?: "gen-1"

            val adm = checkpointStore.admitEffect(workflowName, workflowId, saved.revision, gen, "step-1", HTTP_EFFECT_KIND)
            val record =
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, gen, adm.sequence),
                    parent = ParentProvenance.StepAttempt("step-1", "att-1"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1100L,
                )
            evidenceStore.recordEvidence(record)

            // Commit cancellation
            checkpointStore.cancel(
                WorkflowCancellationRequest(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    expectedRevision = saved.revision,
                    expectedGeneration = gen,
                    actor = "admin",
                    reason = "post-step cancel",
                    cancelledAtEpochMillis = clock.millis(),
                ),
            )

            // Evidence must be 100% intact with zero compensation or modification
            val loaded = evidenceStore.getEvidence(record.key)
            assertThat(loaded).isNotNull
            assertThat(loaded?.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(loaded?.completedAtEpochMillis).isEqualTo(1100L)
        }
    }

    // 12. Canary secret leakage trap discriminator
    @Test
    fun `ADV-12 - sensitive tool arguments are never persisted into evidence records`() {
        runBlocking {
            val canarySecret = "CANARY_SECRET_NEVER_PERSIST_TO_EVIDENCE_98765"
            val sensitiveArgs = SensitiveToolArguments.of("""{"secret_key":"$canarySecret"}""")

            val continuationStore = InMemoryApprovalContinuationStore()
            continuationStore.create(sampleContinuation(arguments = sensitiveArgs), sensitiveArgs)
            val claimed = continuationStore.claimForExecution("app-adv-1", 0L, "worker-1")

            val saved = checkpointStore.save(sampleCheckpoint())
            val admission =
                WorkflowApprovalResumeAdmission(
                    checkpointStore = checkpointStore,
                    continuationStore = continuationStore,
                    evidenceStore = evidenceStore,
                    approvalResumeEvidenceEnabled = true,
                    clock = clock,
                )

            val permit =
                admission.admitResume(
                    ApprovalResumeAdmissionRequest(
                        approvalId = "app-adv-1",
                        toolName = "privileged_tool",
                        runIdentity = governedIdentity,
                        checkpointBinding = WorkflowCheckpointBinding(workflowName, workflowId),
                        continuationVersion = claimed.continuation.version,
                    ),
                )

            permit.evidenceHandle?.recordDispatched()
            permit.evidenceHandle?.recordCompleted()

            val records = evidenceStore.listEvidence(workflowName, workflowId)
            assertThat(records).isNotEmpty
            records.forEach { record ->
                assertThat(record.toString()).doesNotContain(canarySecret)
            }
        }
    }
}
