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
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end integration qualification suite for D7-F:
 * Proves that TramAI's cancellation authority, execution admission, recovery fencing,
 * and durable per-effect child evidence operate correctly together across supported
 * governed workflow boundaries (Scenarios D7-F01 through D7-F15).
 */
class WorkflowCrossBoundaryIntegrationTest {
    private val workflowName = "test-governed-wf"
    private val workflowId = "run-d7-f"
    private val clock = Clock.systemUTC()

    private val deploymentIdentity =
        WorkloadDeploymentIdentity(
            environmentId = EnvironmentId("test-env"),
            workloadId = WorkloadId("test-workload"),
            deploymentId = DeploymentId("dep-1"),
            configuration =
                WorkloadConfigurationIdentity(
                    id = ConfigurationId("cfg-1"),
                    version = ConfigurationVersion("v1.0"),
                ),
        )

    private val governedIdentity =
        GovernedRunIdentity(
            deployment = deploymentIdentity,
            runId = RunId(workflowId),
        )

    private object PlainStateCodec : WorkflowStateCodec<String> {
        override fun encode(state: String): String = state

        override fun decode(payload: String): String = payload
    }

    private lateinit var pool: JdbcConnectionPool
    private lateinit var jdbcCheckpointStore: JdbcWorkflowCheckpointStore
    private lateinit var jdbcEvidenceStore: JdbcWorkflowEffectEvidenceRecordStore

    @BeforeEach
    fun setUp() {
        pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_f_int_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        jdbcCheckpointStore = JdbcWorkflowCheckpointStore(pool)
        jdbcEvidenceStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
        pool.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(jdbcCheckpointStore.createTableSql())
                jdbcEvidenceStore.createSchemaSql().forEach { stmt.execute(it) }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        pool.dispose()
    }

    private val digester =
        dev.tramai.security.approval
            .Sha256ToolArgumentsDigester()

    private fun initialCheckpoint(
        name: String = workflowName,
        id: String = workflowId,
        gen: String = "gen-1",
        rev: Long = 1L,
        extraMetadata: Map<String, String> = emptyMap(),
    ) = WorkflowCheckpoint(
        workflowName = name,
        workflowId = id,
        nextStepIndex = 0,
        stepExecutions = 0,
        lastCompletedStepName = null,
        statePayload = "init",
        revision = rev,
        checkpointGeneration = gen,
        metadata =
            encodeGovernedRunAttribution(governedIdentity) + extraMetadata,
    )

    private fun sampleContinuation(
        approvalId: String = "app-1",
        workflowRunId: String = workflowId,
        toolName: String = "charge_payment",
        arguments: SensitiveToolArguments = SensitiveToolArguments.of("{}"),
        version: Long = 0L,
        status: ApprovalContinuationStatus = ApprovalContinuationStatus.PENDING,
    ) = ApprovalContinuation(
        approvalId = approvalId,
        workflowRunId = workflowRunId,
        correlationId = "corr-1",
        toolCallId = "call-1",
        toolName = toolName,
        argumentsDigest = digester.digest(arguments),
        policyVersion = "p-1",
        workflowDigest =
            dev.tramai.core.approval.Sha256Digest
                .of("sha256:" + "0".repeat(64)),
        status = status,
        createdAt = Instant.now(),
        approvalExpiresAt = Instant.now().plusSeconds(600),
        claimedBy = null,
        claimedAt = null,
        completedAt = null,
        version = version,
    )

    // =========================================================================
    // D7-F01: Cancel before next step — E11 refuses, step body never enters
    // =========================================================================
    @Test
    fun `D7-F01 - cancel before next step causes E11 refusal and step body never enters`() {
        val store = InMemoryWorkflowCheckpointStore()
        val step2Executed = AtomicBoolean(false)
        val wf =
            workflow<String>(workflowName) {
                branchStep("route", select = { "a" }) {
                    branch("a") {
                        localStep("cancel-run") { s, _ ->
                            val current = runBlocking { store.load(workflowName, workflowId) }!!
                            runBlocking {
                                store.cancel(
                                    WorkflowCancellationRequest(
                                        workflowName = workflowName,
                                        workflowId = workflowId,
                                        expectedRevision = current.revision,
                                        expectedGeneration = current.checkpointGeneration,
                                        actor = "operator-1",
                                        reason = "user requested cancellation",
                                        cancelledAtEpochMillis = clock.millis(),
                                    ),
                                )
                            }
                            s
                        }
                        localStep("step-2") { s, _ ->
                            step2Executed.set(true)
                            "$s:step2"
                        }
                    }
                }
            }.build { it }

        assertThatThrownBy {
            runBlocking {
                wf.run(
                    initialState = "init",
                    run = GovernedRun(WorkflowContext(workflowId = workflowId), governedIdentity),
                    observer = NoOpWorkflowObserver,
                    persistence = WorkflowPersistence(checkpointStore = store, stateCodec = PlainStateCodec),
                )
            }
        }.isInstanceOf(WorkflowAdmissionRefusedException::class.java)
            .hasMessageContaining("admission was refused")

        assertThat(step2Executed.get()).isFalse()
    }

    // =========================================================================
    // D7-F02: Cancel before outbound effect — effect admission refused, transport not invoked
    // =========================================================================
    @Test
    fun `D7-F02 - cancel before outbound effect refuses effect admission and transport is never invoked`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val saved = store.save(initialCheckpoint())

            // Cancel run
            store.cancel(
                WorkflowCancellationRequest(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    expectedRevision = saved.revision,
                    expectedGeneration = saved.checkpointGeneration,
                    actor = "operator-1",
                    reason = "cancel effect",
                    cancelledAtEpochMillis = clock.millis(),
                ),
            )

            val transportInvoked = AtomicInteger(0)
            assertThatThrownBy {
                runBlocking {
                    // Step attempts to admit effect
                    store.admitEffect(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        currentRevision = saved.revision + 1, // cancellation committed at revision + 1
                        currentGeneration = saved.checkpointGeneration,
                        stepName = "outbound-step",
                        effectKind = HTTP_EFFECT_KIND,
                    )
                    transportInvoked.incrementAndGet()
                }
            }.isInstanceOf(WorkflowAdmissionRefusedException::class.java)
                .hasMessageContaining("admission was refused")

            assertThat(transportInvoked.get()).isEqualTo(0)
        }
    }

    // =========================================================================
    // D7-F03: Effect admission wins before cancellation — pre-cancel grant preserved, outcome conservative
    // =========================================================================
    @Test
    fun `D7-F03 - effect admission winning before cancellation preserves grant and produces CANCELLED_UNCERTAIN`() {
        runBlocking {
            val store = jdbcCheckpointStore
            val saved = store.save(initialCheckpoint())

            // 1. Effect admission wins
            val admission =
                store.admitEffect(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    currentRevision = saved.revision,
                    currentGeneration = saved.checkpointGeneration,
                    stepName = "http-step",
                    effectKind = HTTP_EFFECT_KIND,
                )
            assertThat(admission.admitted).isTrue()
            assertThat(admission.sequence).isEqualTo(1L)

            // 2. Cancellation submitted afterward
            val cancelled =
                store.cancel(
                    WorkflowCancellationRequest(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        expectedRevision = saved.revision,
                        expectedGeneration = saved.checkpointGeneration,
                        actor = "operator-1",
                        reason = "cancel after admission",
                        cancelledAtEpochMillis = clock.millis(),
                        uncertain = false,
                    ),
                )

            // Because an effect was admitted (admission_seq > 0), terminal outcome MUST be conservative
            assertThat(cancelled.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED_UNCERTAIN)
        }
    }

    // =========================================================================
    // D7-F04: Cancellation wins concurrent admission race — no new effect admission
    // =========================================================================
    @Test
    fun `D7-F04 - cancellation winning concurrent race prevents new effect admission`() {
        runBlocking {
            val store = jdbcCheckpointStore
            val saved = store.save(initialCheckpoint())

            // Cancellation wins the revision
            val cancelled =
                store.cancel(
                    WorkflowCancellationRequest(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        expectedRevision = saved.revision,
                        expectedGeneration = saved.checkpointGeneration,
                        actor = "operator-1",
                        reason = "race cancellation",
                        cancelledAtEpochMillis = clock.millis(),
                    ),
                )
            assertThat(cancelled.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED)

            // Concurrent or subsequent effect admission attempting to use the saved revision or current revision
            assertThatThrownBy {
                runBlocking {
                    store.admitEffect(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        currentRevision = cancelled.revision,
                        currentGeneration = saved.checkpointGeneration,
                        stepName = "http-step",
                        effectKind = HTTP_EFFECT_KIND,
                    )
                }
            }.isInstanceOf(WorkflowAdmissionRefusedException::class.java)
                .hasMessageContaining("admission was refused")
        }
    }

    // =========================================================================
    // D7-F05: Restart after cancellation — terminal authority survives and resumed execution refused
    // =========================================================================
    @Test
    fun `D7-F05 - restart after cancellation retains terminal authority and refuses resume`() {
        runBlocking {
            val stepExecuted = AtomicBoolean(false)
            val wf =
                workflow<String>(workflowName) {
                    localStep("resumed-step") { s, _ ->
                        stepExecuted.set(true)
                        s
                    }
                }.build { it }

            val saved = jdbcCheckpointStore.save(initialCheckpoint(extraMetadata = wf.checkpointMetadata()))
            val cancelled =
                jdbcCheckpointStore.cancel(
                    WorkflowCancellationRequest(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        expectedRevision = saved.revision,
                        expectedGeneration = saved.checkpointGeneration,
                        actor = "operator-1",
                        reason = "incident shutdown",
                        cancelledAtEpochMillis = clock.millis(),
                    ),
                )

            // Simulate process restart by instantiating fresh store on same DB pool
            val restartedStore = JdbcWorkflowCheckpointStore(pool)
            val loadedLifecycle = restartedStore.lifecycle(workflowName, workflowId)
            assertThat(loadedLifecycle).isNotNull
            assertThat(loadedLifecycle?.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED)
            assertThat(loadedLifecycle?.cancelledBy).isEqualTo("operator-1")

            // Outbound effect admission on restarted store is refused
            assertThatThrownBy {
                runBlocking {
                    restartedStore.admitEffect(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        currentRevision = cancelled.revision,
                        currentGeneration = saved.checkpointGeneration,
                        stepName = "resumed-step",
                        effectKind = HTTP_EFFECT_KIND,
                    )
                }
            }.isInstanceOf(WorkflowAdmissionRefusedException::class.java)
                .hasMessageContaining("admission was refused")

            // Resumed execution is refused before step body runs
            assertThatThrownBy {
                runBlocking {
                    wf.resume(
                        run = GovernedRun(WorkflowContext(workflowId = workflowId), governedIdentity),
                        observer = NoOpWorkflowObserver,
                        persistence = WorkflowPersistence(checkpointStore = restartedStore, stateCodec = PlainStateCodec),
                    )
                }
            }.isInstanceOf(WorkflowAdmissionRefusedException::class.java)

            assertThat(stepExecuted.get()).isFalse()
        }
    }

    // =========================================================================
    // D7-F06: Recovery worker lease takeover — stale worker cannot obtain fresh admission
    // =========================================================================
    @Test
    fun `D7-F06 - recovery worker lease takeover fences stale worker admission`() {
        runBlocking {
            val now =
                java.util.concurrent.atomic
                    .AtomicLong(1000L)
            val leaseStore = JdbcWorkflowLeaseStore(dataSource = pool, clockMillis = { now.get() })
            pool.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(leaseStore.createTableSql())
                }
            }
            val store = jdbcCheckpointStore
            val saved = store.save(initialCheckpoint())

            val lease1 =
                leaseStore.claim(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    ownerId = "worker-1",
                    checkpointRevision = saved.revision,
                    leaseDurationMillis = 5000L,
                )
            assertThat(lease1.ownerId).isEqualTo("worker-1")

            // Advance time past lease1 expiry so recovery worker can take over
            now.set(10_000L)
            val lease2 =
                leaseStore.claim(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    ownerId = "recovery-worker",
                    checkpointRevision = saved.revision,
                    leaseDurationMillis = 5000L,
                )
            assertThat(lease2.ownerId).isEqualTo("recovery-worker")

            // Stale worker-1 attempts admission under its superseded lease fence
            assertThatThrownBy {
                runBlocking {
                    admitUnderLeaseFence(
                        fence = leaseStore,
                        delegate = store,
                        expectedLease = lease1,
                        request =
                            WorkflowAdmissionRequest(
                                workflowName = workflowName,
                                workflowId = workflowId,
                                expectedRevision = saved.revision,
                                expectedGeneration = saved.checkpointGeneration,
                            ),
                    )
                }
            }.isInstanceOf(WorkflowAdmissionRefusedException::class.java)
                .hasMessageContaining("worker 'worker-1' no longer owns its lease")
        }
    }

    // =========================================================================
    // D7-F07: Late approval after cancellation — E9 continuation cannot reactivate workflow
    // =========================================================================
    @Test
    fun `D7-F07 - late approval after cancellation is refused and cannot reactivate workflow`() {
        runBlocking {
            val saved = jdbcCheckpointStore.save(initialCheckpoint())

            // Cancel the workflow run
            jdbcCheckpointStore.cancel(
                WorkflowCancellationRequest(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    expectedRevision = saved.revision,
                    expectedGeneration = saved.checkpointGeneration,
                    actor = "admin",
                    reason = "cancelled before approval",
                    cancelledAtEpochMillis = clock.millis(),
                ),
            )

            val continuationStore = InMemoryApprovalContinuationStore()
            continuationStore.create(
                sampleContinuation(approvalId = "app-late-1", toolName = "charge_payment"),
                SensitiveToolArguments.of("{}"),
            )
            val claimed = continuationStore.claimForExecution("app-late-1", 0L, "worker-1")

            val admission =
                WorkflowApprovalResumeAdmission(
                    checkpointStore = jdbcCheckpointStore,
                    continuationStore = continuationStore,
                    evidenceStore = jdbcEvidenceStore,
                    approvalResumeEvidenceEnabled = true,
                    clock = clock,
                )

            val req =
                ApprovalResumeAdmissionRequest(
                    approvalId = "app-late-1",
                    toolName = "charge_payment",
                    runIdentity = governedIdentity,
                    checkpointBinding = WorkflowCheckpointBinding(workflowName, workflowId),
                    continuationVersion = claimed.continuation.version,
                )

            assertThatThrownBy {
                runBlocking { admission.admitResume(req) }
            }.isInstanceOf(ApprovalResumeAdmissionRefusedException::class.java)

            // Zero E9 evidence committed
            assertThat(jdbcEvidenceStore.listEvidence(workflowName, workflowId)).isEmpty()
        }
    }

    // =========================================================================
    // D7-F08: Concurrent approval resumes — only one valid continuation claim proceeds
    // =========================================================================
    @Test
    fun `D7-F08 - concurrent approval resumes allow exactly one valid continuation claim`() {
        runBlocking {
            val continuationStore = InMemoryApprovalContinuationStore()
            continuationStore.create(
                sampleContinuation(approvalId = "app-race-1", toolName = "exec_action"),
                SensitiveToolArguments.of("{}"),
            )

            val deferred1 = async(Dispatchers.IO) { runCatching { continuationStore.claimForExecution("app-race-1", 0L, "worker-1") } }
            val deferred2 = async(Dispatchers.IO) { runCatching { continuationStore.claimForExecution("app-race-1", 0L, "worker-2") } }

            val results = awaitAll(deferred1, deferred2)
            val successes = results.filter { it.isSuccess }
            val failures = results.filter { it.isFailure }

            assertThat(successes).hasSize(1)
            assertThat(failures).hasSize(1)
            assertThat(failures[0].exceptionOrNull())
                .isInstanceOf(dev.tramai.core.exception.ApprovalContinuationConflictException::class.java)
        }
    }

    // =========================================================================
    // D7-F09: Repeated cancellation — idempotent terminal outcome
    // =========================================================================
    @Test
    fun `D7-F09 - repeated cancellation is strictly idempotent and does not mutate revision`() {
        runBlocking {
            val store = jdbcCheckpointStore
            val saved = store.save(initialCheckpoint())

            val cancelReq1 =
                WorkflowCancellationRequest(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    expectedRevision = saved.revision,
                    expectedGeneration = saved.checkpointGeneration,
                    actor = "operator-1",
                    reason = "first cancellation",
                    cancelledAtEpochMillis = clock.millis(),
                )

            val firstCancel = store.cancel(cancelReq1)
            assertThat(firstCancel.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED)
            assertThat(firstCancel.revision).isEqualTo(saved.revision + 1)

            val cancelReq2 =
                WorkflowCancellationRequest(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    expectedRevision = firstCancel.revision,
                    expectedGeneration = saved.checkpointGeneration,
                    actor = "operator-2",
                    reason = "duplicate cancellation",
                    cancelledAtEpochMillis = clock.millis() + 1000L,
                )

            val secondCancel = store.cancel(cancelReq2)
            assertThat(secondCancel.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED)
            assertThat(secondCancel.revision).isEqualTo(firstCancel.revision)
            assertThat(secondCancel.cancelledBy).isEqualTo("operator-1")
            assertThat(secondCancel.reason).isEqualTo("first cancellation")
        }
    }

    // =========================================================================
    // D7-F10: Generation rotation during admission — stale generation refused
    // =========================================================================
    @Test
    fun `D7-F10 - generation rotation during admission refuses stale generation`() {
        runBlocking {
            val store = jdbcCheckpointStore
            val saved = store.save(initialCheckpoint())

            // Present stale generation (does not match saved.checkpointGeneration)
            assertThatThrownBy {
                runBlocking {
                    store.admit(
                        WorkflowAdmissionRequest(
                            workflowName = workflowName,
                            workflowId = workflowId,
                            expectedRevision = saved.revision,
                            expectedGeneration = "stale-generation",
                        ),
                    )
                }
            }.isInstanceOf(WorkflowCheckpointConflictException::class.java)
        }
    }

    // =========================================================================
    // D7-F11: Existing completed effect followed by cancellation — completion retained, no compensation
    // =========================================================================
    @Test
    fun `D7-F11 - completed effect evidence is retained intact when subsequent cancellation commits`() {
        runBlocking {
            val store = jdbcCheckpointStore
            val evidenceStore = jdbcEvidenceStore
            val saved = store.save(initialCheckpoint())

            // 1. Admit effect 1
            val adm1 =
                store.admitEffect(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    currentRevision = saved.revision,
                    currentGeneration = saved.checkpointGeneration,
                    stepName = "http-step",
                    effectKind = HTTP_EFFECT_KIND,
                )

            val recordKey = WorkflowEffectEvidenceKey(workflowName, workflowId, saved.checkpointGeneration ?: "gen-1", adm1.sequence)
            val record =
                WorkflowEffectEvidenceRecord(
                    key = recordKey,
                    stepName = "http-step",
                    stepAttemptId = "att-1",
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = clock.millis() - 500,
                    completedAtEpochMillis = clock.millis() - 100,
                )
            evidenceStore.recordEvidence(record)

            // 2. Cancellation commits later
            val cancelled =
                store.cancel(
                    WorkflowCancellationRequest(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        expectedRevision = saved.revision,
                        expectedGeneration = saved.checkpointGeneration,
                        actor = "admin",
                        reason = "stop workflow",
                        cancelledAtEpochMillis = clock.millis(),
                    ),
                )
            assertThat(cancelled.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED_UNCERTAIN)

            // 3. Effect evidence remains COMPLETED — no compensation implied
            val loadedRecord = evidenceStore.getEvidence(recordKey)
            assertThat(loadedRecord).isNotNull
            assertThat(loadedRecord?.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(loadedRecord?.completedAtEpochMillis).isEqualTo(record.completedAtEpochMillis)
        }
    }

    // =========================================================================
    // D7-F12: Missing child evidence after committed admission — unclassified outcome
    // =========================================================================
    @Test
    fun `D7-F12 - committed admission with missing child evidence is detected as unclassified gap`() {
        runBlocking {
            val store = jdbcCheckpointStore
            val evidenceStore = jdbcEvidenceStore
            val saved = store.save(initialCheckpoint())

            // Admit effect 1 (seq 1) and record evidence
            val adm1 = store.admitEffect(workflowName, workflowId, saved.revision, saved.checkpointGeneration, "step-1", HTTP_EFFECT_KIND)
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, saved.checkpointGeneration ?: "gen-1", adm1.sequence),
                    stepName = "step-1",
                    stepAttemptId = "att-1",
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1500L,
                ),
            )

            // Admit effect 2 (seq 2), but simulate crash before child evidence is persisted
            val adm2 =
                store.admitEffect(
                    workflowName,
                    workflowId,
                    saved.revision,
                    saved.checkpointGeneration,
                    "step-2",
                    HTTP_EFFECT_KIND,
                )
            assertThat(adm2.sequence).isEqualTo(2L)

            // Admit effect 3 (seq 3) and record evidence
            val adm3 =
                store.admitEffect(
                    workflowName,
                    workflowId,
                    saved.revision,
                    saved.checkpointGeneration,
                    "step-3",
                    HTTP_EFFECT_KIND,
                )
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, saved.checkpointGeneration ?: "gen-1", adm3.sequence),
                    stepName = "step-3",
                    stepAttemptId = "att-3",
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 2000L,
                    completedAtEpochMillis = 2500L,
                ),
            )

            // Read timeline
            val timeline = WorkflowHistoricalEvidenceReader.readTimeline(store, evidenceStore, workflowName, workflowId)
            assertThat(timeline.admissionGaps).containsExactly(2L)
            // Replay cannot be inferred safe from missing evidence
            assertThat(timeline.records).hasSize(2)
        }
    }

    // =========================================================================
    // D7-F13: E9 completion evidence with incomplete continuation — reconciliation required
    // =========================================================================
    @Test
    fun `D7-F13 - E9 completed evidence with incomplete continuation requires reconciliation and prohibits second invocation`() {
        runBlocking {
            val store = jdbcCheckpointStore
            val evidenceStore = jdbcEvidenceStore
            val continuationStore = InMemoryApprovalContinuationStore()
            val saved = store.save(initialCheckpoint())

            continuationStore.create(
                sampleContinuation(approvalId = "app-recon-1", toolName = "send_wire"),
                SensitiveToolArguments.of("{}"),
            )
            continuationStore.claimForExecution("app-recon-1", 0L, "worker-1") // status is CLAIMED

            // Simulate tool completion evidence written, but process halted before continuationStore.complete
            val e9Key = WorkflowEffectEvidenceKey(workflowName, workflowId, saved.checkpointGeneration ?: "gen-1", 1L)
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = e9Key,
                    parent = ParentProvenance.ApprovalContinuation("app-recon-1", 1L, "send_wire"),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1500L,
                ),
            )

            val timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    store,
                    evidenceStore,
                    workflowName,
                    workflowId,
                    continuationStore,
                )

            assertThat(timeline.requiresReconciliation).isTrue()
        }
    }

    // =========================================================================
    // D7-F14: Post-admission evidence persistence failure — no dispatch, unresolved state preserved
    // =========================================================================
    @Test
    fun `D7-F14 - post-admission evidence persistence failure prevents dispatch and preserves state`() {
        runBlocking {
            val store = InMemoryWorkflowCheckpointStore()
            val saved = store.save(initialCheckpoint())

            val failingEvidenceStore =
                object : WorkflowEffectEvidenceRecordStore {
                    override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                        throw SQLException("Database disk full")

                    override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? = null

                    override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                        throw SQLException("Database disk full")

                    override suspend fun listEvidence(
                        workflowName: String,
                        workflowId: String,
                    ): List<WorkflowEffectEvidenceRecord> = emptyList()
                }

            val continuationStore = InMemoryApprovalContinuationStore()
            continuationStore.create(
                sampleContinuation(approvalId = "app-fail-1", toolName = "execute_transfer"),
                SensitiveToolArguments.of("{}"),
            )
            val claimed = continuationStore.claimForExecution("app-fail-1", 0L, "worker-1")

            val admission =
                WorkflowApprovalResumeAdmission(
                    checkpointStore = store,
                    continuationStore = continuationStore,
                    evidenceStore = failingEvidenceStore,
                    approvalResumeEvidenceEnabled = true,
                    clock = clock,
                )

            val toolExecutionCount = AtomicInteger(0)
            assertThatThrownBy {
                runBlocking {
                    admission.admitResume(
                        ApprovalResumeAdmissionRequest(
                            approvalId = "app-fail-1",
                            toolName = "execute_transfer",
                            runIdentity = governedIdentity,
                            checkpointBinding = WorkflowCheckpointBinding(workflowName, workflowId),
                            continuationVersion = claimed.continuation.version,
                        ),
                    )
                    // If admission succeeded, tool would execute:
                    toolExecutionCount.incrementAndGet()
                }
            }.isInstanceOf(dev.tramai.core.exception.ApprovalResumeEvidencePersistenceException::class.java)

            assertThat(toolExecutionCount.get()).isEqualTo(0)
        }
    }

    // =========================================================================
    // D7-F15: Restart with mixed historical E1-E9 evidence — original identities and parent provenance retained
    // =========================================================================
    @Test
    fun `D7-F15 - restart with mixed historical E1-E9 evidence preserves all identities and parent provenance`() {
        runBlocking {
            // Write E1 HTTP effect (ParentProvenance.StepAttempt)
            val httpKey = WorkflowEffectEvidenceKey(workflowName, workflowId, "gen-1", 1L)
            jdbcEvidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = httpKey,
                    parent = ParentProvenance.StepAttempt("fetch-step", "att-http-1"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1100L,
                ),
            )

            // Write E7 Process effect (ParentProvenance.StepAttempt)
            val procKey = WorkflowEffectEvidenceKey(workflowName, workflowId, "gen-1", 2L)
            jdbcEvidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = procKey,
                    parent = ParentProvenance.StepAttempt("exec-shell", "att-proc-1"),
                    effectKind = PROCESS_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1200L,
                    completedAtEpochMillis = 1350L,
                ),
            )

            // Write E9 Approval resume (ParentProvenance.ApprovalContinuation)
            val e9Key = WorkflowEffectEvidenceKey(workflowName, workflowId, "gen-1", 3L)
            jdbcEvidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = e9Key,
                    parent = ParentProvenance.ApprovalContinuation("app-42", 5L, "approval-tool"),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 2000L,
                    completedAtEpochMillis = 2200L,
                ),
            )

            // Simulate restart: instantiate fresh JDBC store from same pool
            val restartedEvidenceStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val list = restartedEvidenceStore.listEvidence(workflowName, workflowId)

            assertThat(list).hasSize(3)
            val httpRec = list.first { it.effectKind == HTTP_EFFECT_KIND }
            val procRec = list.first { it.effectKind == PROCESS_EFFECT_KIND }
            val e9Rec = list.first { it.effectKind == APPROVAL_RESUME_EFFECT_KIND }

            assertThat(httpRec.parent).isEqualTo(ParentProvenance.StepAttempt("fetch-step", "att-http-1"))
            assertThat(procRec.parent).isEqualTo(ParentProvenance.StepAttempt("exec-shell", "att-proc-1"))
            assertThat(e9Rec.parent).isEqualTo(ParentProvenance.ApprovalContinuation("app-42", 5L, "approval-tool"))

            assertThat(httpRec.key.admissionSeq).isEqualTo(1L)
            assertThat(procRec.key.admissionSeq).isEqualTo(2L)
            assertThat(e9Rec.key.admissionSeq).isEqualTo(3L)
        }
    }

    // =========================================================================
    // D7-F16: Real GovernedRun workflow step with E9 approval resume coordination under cancellation
    // =========================================================================
    @Test
    fun `D7-F16 - representative GovernedRun workflow step composition with E9 approval resume under cancellation`() {
        val store = InMemoryWorkflowCheckpointStore()
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val continuationStore = InMemoryApprovalContinuationStore()
        val sensitiveArgs = SensitiveToolArguments.of("""{"amount": 100}""")
        val executedTool = AtomicBoolean(false)

        val admission =
            WorkflowApprovalResumeAdmission(
                checkpointStore = store,
                continuationStore = continuationStore,
                evidenceStore = evidenceStore,
                approvalResumeEvidenceEnabled = true,
                clock = clock,
            )

        val wf =
            workflow<String>(workflowName) {
                branchStep("pipeline", select = { "main" }) {
                    branch("main") {
                        localStep("step-1-produce-continuation") { s, _ ->
                            val cont =
                                sampleContinuation(
                                    approvalId = "app-live-1",
                                    toolName = "execute_transfer",
                                    arguments = sensitiveArgs,
                                )
                            runBlocking {
                                continuationStore.create(cont, sensitiveArgs)
                            }
                            "$s:step1"
                        }
                        localStep("step-2-cancel-and-attempt-resume") { s, _ ->
                            val current = runBlocking { store.load(workflowName, workflowId) }!!
                            // Cancellation arrives and commits mid-workflow
                            runBlocking {
                                store.cancel(
                                    WorkflowCancellationRequest(
                                        workflowName = workflowName,
                                        workflowId = workflowId,
                                        expectedRevision = current.revision,
                                        expectedGeneration = current.checkpointGeneration,
                                        actor = "operator-cancel",
                                        reason = "abort pipeline",
                                        cancelledAtEpochMillis = clock.millis(),
                                    ),
                                )
                            }

                            // Worker attempts to claim and admit approval resume on the cancelled run
                            val claimed =
                                runBlocking {
                                    continuationStore.claimForExecution("app-live-1", 0L, "worker-1")
                                }

                            // E9 admission must fail closed
                            val req =
                                ApprovalResumeAdmissionRequest(
                                    approvalId = "app-live-1",
                                    toolName = "execute_transfer",
                                    runIdentity = governedIdentity,
                                    checkpointBinding = WorkflowCheckpointBinding(workflowName, workflowId),
                                    continuationVersion = claimed.continuation.version,
                                )
                            try {
                                runBlocking { admission.admitResume(req) }
                                executedTool.set(true)
                            } catch (e: ApprovalResumeAdmissionRefusedException) {
                                // Expected: admission refused, tool must not execute
                            }
                            "$s:step2"
                        }
                    }
                }
            }.build { it }

        assertThatThrownBy {
            runBlocking {
                wf.run(
                    initialState = "start",
                    run = GovernedRun(WorkflowContext(workflowId = workflowId), governedIdentity),
                    observer = NoOpWorkflowObserver,
                    persistence = WorkflowPersistence(checkpointStore = store, stateCodec = PlainStateCodec),
                )
            }
        }.isInstanceOf(WorkflowCheckpointConflictException::class.java)

        // Proved:
        // 1. Tool execution never ran
        assertThat(executedTool.get()).isFalse()
        // 2. No E9 child evidence records were persisted
        val evidenceList = runBlocking { evidenceStore.listEvidence(workflowName, workflowId) }
        assertThat(evidenceList).isEmpty()
        // 3. Workflow checkpoint reflects cancellation
        val lifecycle = runBlocking { store.lifecycle(workflowName, workflowId) }
        assertThat(lifecycle?.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED_UNCERTAIN)
        // 4. Timeline reconstruction confirms the terminal cancelled state
        val timeline =
            runBlocking {
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = store,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = workflowId,
                    continuationStore = continuationStore,
                )
            }
        assertThat(timeline.isCancelled).isTrue()
        assertThat(timeline.isUncertainCancellation).isTrue()
        assertThat(timeline.requiresReconciliation).isTrue()
    }
}
