@file:Suppress("LargeClass", "MaxLineLength", "ktlint:standard:max-line-length", "LongMethod")

package dev.tramai.orchestration

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deterministic qualification test suite for D7-D durable External Step Executor evidence (Boundary E8).
 *
 * Covers scenarios EXT-01 through EXT-19:
 * - EXT-01: Successful governed execution with real worker and JDBC stores (COMPLETED)
 * - EXT-02: Missing authority: Refused before admission (WorkflowEvidenceResolutionException)
 * - EXT-03: Missing step attempt: Refused before admission (WorkflowEvidenceResolutionException)
 * - EXT-04: ADMITTED write failure: Resolver never invoked (ExternalEvidencePersistenceException)
 * - EXT-05: Admission before resolver failure: No false DISPATCHED (UNCERTAIN, external_resolution_failed)
 * - EXT-06: Executor creation succeeds, execution throws: UNCERTAIN, no false completion (external_execution_failed)
 * - EXT-07: Executor succeeds: DISPATCHED then COMPLETED
 * - EXT-08: COMPLETED write fails: Invocation not repeated; failure surfaced (external_evidence_failed)
 * - EXT-09: Merge callback fails: E8 remains COMPLETED
 * - EXT-10: Parent cancellation: Original cancellation identity preserved (external_cancelled)
 * - EXT-11: Multiple E8 effects in one attempt: Distinct sequences, shared authoritative parent
 * - EXT-12: Child coroutine execution: Inherits parent attempt context and generation
 * - EXT-13: Sequential step attempts: Distinct evidence attribution and generations without cross-attempt interference
 * - EXT-14: Ungoverned workflow: No evidence enforcement or writes
 * - EXT-15: Unknown plugin type: No pre-admission resolver callback
 * - EXT-16: Sensitive-data injection: No secrets in stored evidence
 * - EXT-17: Missing child evidence lookup: Fail closed
 * - EXT-18: Duplicate permit consumption: Second invocation refused
 * - EXT-19: Persistence exception createCopy: Retains cause and suppressed across coroutine boundaries
 */
class WorkflowExternalEffectEvidenceTest {
    private val workflowName = "test-ext-workflow"
    private val workflowId = "run-ext-1"
    private val stepName = "ext-step"
    private val attemptId = "att-ext-1"

    private fun testContext(
        admission: WorkflowExternalAdmission? = null,
        evidenceStore: WorkflowEffectEvidenceRecordStore? = null,
        attempt: PersistedStepAttempt? = PersistedStepAttempt(workflowName, workflowId, stepName, attemptId),
        clock: Clock = Clock.systemUTC(),
    ) = WorkflowExternalAdmissionScope(admission) +
        WorkflowEffectEvidenceStoreScope(evidenceStore, clock) +
        (if (attempt != null) StepAttemptScope(attempt) else kotlin.coroutines.EmptyCoroutineContext)

    private fun createPluginStep(
        name: String = stepName,
        type: String = "test-plugin",
        config: Map<String, Any?> = emptyMap(),
        merge: suspend (String, Map<String, Any?>, WorkflowContext) -> String = { state, result, _ ->
            "$state:${result["output"]}"
        },
    ): PluginWorkflowStep<String> = PluginWorkflowStep(name = name, type = type, config = config, merge = merge)

    @Test
    fun `EXT-01 - Successful governed execution with real worker and JDBC stores`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_ext_01_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val fixture =
                createJdbcWorkerFixture(
                    pool = pool,
                    schemaStore = delegateStore,
                    executor = ExternalStepExecutor { spec -> mapOf("output" to "done-${spec["param"]}") },
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            fixture.supervisor.attachScope(scope)

            runBlocking {
                val initialCheckpoint = saveGovernedInitialCheckpoint(fixture)
                val expectedGeneration = checkNotNull(initialCheckpoint.checkpointGeneration)
                val lease =
                    fixture.leaseStore.claim(workflowName, workflowId, "worker-e2e-1", initialCheckpoint.revision, 5_000)
                fixture.supervisor.launch(initialCheckpoint, lease)

                withTimeout(10_000) {
                    while (fixture.supervisor.activeExecutionCount() != 0) delay(10)
                }

                assertThat(fixture.supervisor.latestFailure(workflowId)).isNull()
                val evidenceList = delegateStore.listEvidence(workflowName, workflowId)
                assertThat(evidenceList).hasSize(1)

                val record = evidenceList.single()
                assertThat(record.key.workflowName).isEqualTo(workflowName)
                assertThat(record.key.workflowId).isEqualTo(workflowId)
                assertThat(record.key.checkpointGeneration).isEqualTo(expectedGeneration)
                assertThat(record.key.admissionSeq).isEqualTo(2L) // 1 was step admission E11, 2 is external admission E8
                assertThat(record.stepName).isEqualTo(stepName)
                assertThat(record.stepAttemptId).isEqualTo("attempt-e2e-1")
                assertThat(record.effectKind).isEqualTo(externalEffectKind("custom-plugin"))
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                assertThat(record.createdAtEpochMillis).isGreaterThan(0L)
                assertThat(record.completedAtEpochMillis).isNotNull()
                assertThat(record.completedAtEpochMillis).isGreaterThanOrEqualTo(record.createdAtEpochMillis)
                assertThat(record.diagnosticSummary).isNull()

                // Direct JDBC verification to prove authoritative persistence in H2 table
                pool.connection.use { conn ->
                    conn
                        .prepareStatement(
                            "SELECT workflow_name, workflow_id, checkpoint_generation, admission_seq, step_name, " +
                                "step_attempt_id, effect_kind, state, created_at_epoch_millis, completed_at_epoch_millis, " +
                                "diagnostic_summary FROM tramai_workflow_effect_evidence WHERE workflow_name = ? AND workflow_id = ?",
                        ).use { stmt ->
                            stmt.setString(1, workflowName)
                            stmt.setString(2, workflowId)
                            val rs = stmt.executeQuery()
                            assertThat(rs.next()).isTrue()
                            assertThat(rs.getString("workflow_name")).isEqualTo(workflowName)
                            assertThat(rs.getString("workflow_id")).isEqualTo(workflowId)
                            assertThat(rs.getString("checkpoint_generation")).isEqualTo(expectedGeneration)
                            assertThat(rs.getLong("admission_seq")).isEqualTo(2L)
                            assertThat(rs.getString("step_name")).isEqualTo(stepName)
                            assertThat(rs.getString("step_attempt_id")).isEqualTo("attempt-e2e-1")
                            assertThat(rs.getString("effect_kind")).isEqualTo(externalEffectKind("custom-plugin"))
                            assertThat(rs.getString("state")).isEqualTo("COMPLETED")
                            assertThat(rs.getLong("created_at_epoch_millis")).isEqualTo(record.createdAtEpochMillis)
                            assertThat(rs.getLong("completed_at_epoch_millis")).isEqualTo(record.completedAtEpochMillis)
                            assertThat(rs.getString("diagnostic_summary")).isNull()
                            assertThat(rs.next()).isFalse()
                        }
                }
            }
            scope.cancel()
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `EXT-02 - Missing authority Refused before admission`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val resolver = SimpleResolver(CountingExecutor())
        val step = createPluginStep()

        runBlocking {
            withContext(testContext(admission = null, evidenceStore = evidenceStore)) {
                val error =
                    assertFailsWith<WorkflowEvidenceResolutionException> {
                        step.execute(
                            workflowName = workflowName,
                            state = "initial",
                            context = WorkflowContext(workflowId = workflowId),
                            executorResolver = resolver,
                        )
                    }
                assertThat(
                    error.message,
                ).contains(
                    "per-effect evidence tracking is enabled, but authoritative step-attempt lineage or admission authority is absent",
                )
                assertThat(evidenceStore.listEvidence(workflowName, workflowId)).isEmpty()
                assertThat(resolver.counter.creates.get()).isEqualTo(0)
                assertThat(resolver.counter.executions.get()).isEqualTo(0)
            }
        }
    }

    @Test
    fun `EXT-03 - Missing step attempt Refused before admission`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L)
            }
        val resolver = SimpleResolver(CountingExecutor())
        val step = createPluginStep()

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = evidenceStore, attempt = null)) {
                val error =
                    assertFailsWith<WorkflowEvidenceResolutionException> {
                        step.execute(
                            workflowName = workflowName,
                            state = "initial",
                            context = WorkflowContext(workflowId = workflowId),
                            executorResolver = resolver,
                        )
                    }
                assertThat(
                    error.message,
                ).contains(
                    "per-effect evidence tracking is enabled, but authoritative step-attempt lineage or admission authority is absent",
                )
                assertThat(evidenceStore.listEvidence(workflowName, workflowId)).isEmpty()
                assertThat(resolver.counter.creates.get()).isEqualTo(0)
                assertThat(resolver.counter.executions.get()).isEqualTo(0)
            }
        }
    }

    @Test
    fun `EXT-04 - ADMITTED write failure Resolver never invoked`() {
        val failingStore =
            object : WorkflowEffectEvidenceRecordStore {
                override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                    error("Store disk full simulated failure")

                override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? = null

                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord = record

                override suspend fun listEvidence(
                    workflowName: String,
                    workflowId: String,
                ): List<WorkflowEffectEvidenceRecord> = emptyList()
            }

        val permitGiven = AtomicBoolean(false)
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                permitGiven.set(true)
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-1")
            }
        val resolver = SimpleResolver(CountingExecutor())
        val step = createPluginStep()

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = failingStore)) {
                val error =
                    assertFailsWith<ExternalEvidencePersistenceException> {
                        step.execute(
                            workflowName = workflowName,
                            state = "initial",
                            context = WorkflowContext(workflowId = workflowId),
                            executorResolver = resolver,
                        )
                    }
                assertThat(error.cause).hasMessageContaining("Store disk full simulated failure")
                assertThat(permitGiven.get()).isTrue()
                assertThat(resolver.counter.creates.get()).isEqualTo(0)
                assertThat(resolver.counter.executions.get()).isEqualTo(0)
            }
        }
    }

    @Test
    fun `EXT-05 - Admission before resolver failure No false DISPATCHED`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-1")
            }
        val failingResolver =
            object : ExternalStepExecutorResolver {
                override fun isRegistered(typeId: String): Boolean = true

                override fun registeredTypeIds(): Set<String> = setOf("test-plugin")

                override fun create(typeId: String): ExternalStepExecutor = error("Resolver creation crashed")
            }
        val step = createPluginStep()

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = evidenceStore)) {
                val error =
                    assertFailsWith<IllegalStateException> {
                        step.execute(
                            workflowName = workflowName,
                            state = "initial",
                            context = WorkflowContext(workflowId = workflowId),
                            executorResolver = failingResolver,
                        )
                    }
                assertThat(error).hasMessage("Resolver creation crashed")

                val records = evidenceStore.listEvidence(workflowName, workflowId)
                assertThat(records).hasSize(1)
                val record = records.single()
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
                assertThat(record.diagnosticSummary).isEqualTo(EXTERNAL_RESOLUTION_FAILED_DIAGNOSTIC)
                assertThat(record.completedAtEpochMillis).isNull()
            }
        }
    }

    @Test
    fun `EXT-06 - Executor creation succeeds, execution throws UNCERTAIN, no false completion`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-1")
            }
        val countingExecutor = CountingExecutor(failOnExecute = RuntimeException("Plugin execution threw an error"))
        val resolver = SimpleResolver(countingExecutor)
        val step = createPluginStep()

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = evidenceStore)) {
                val error =
                    assertFailsWith<RuntimeException> {
                        step.execute(
                            workflowName = workflowName,
                            state = "initial",
                            context = WorkflowContext(workflowId = workflowId),
                            executorResolver = resolver,
                        )
                    }
                assertThat(error).hasMessage("Plugin execution threw an error")
                assertThat(countingExecutor.creates.get()).isEqualTo(1)
                assertThat(countingExecutor.executions.get()).isEqualTo(1)

                val records = evidenceStore.listEvidence(workflowName, workflowId)
                assertThat(records).hasSize(1)
                val record = records.single()
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
                assertThat(record.diagnosticSummary).isEqualTo(EXTERNAL_EXECUTION_FAILED_DIAGNOSTIC)
                assertThat(record.completedAtEpochMillis).isNull()
            }
        }
    }

    @Test
    fun `EXT-07 - Executor succeeds DISPATCHED then COMPLETED`() {
        val delegateStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val observedStates = mutableListOf<WorkflowEffectEvidenceState>()
        val observingStore =
            object : WorkflowEffectEvidenceRecordStore by delegateStore {
                override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                    observedStates += record.state
                    return delegateStore.recordEvidence(record)
                }

                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                    observedStates += record.state
                    return delegateStore.updateEvidence(record)
                }
            }

        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-1")
            }
        val countingExecutor = CountingExecutor()
        val resolver = SimpleResolver(countingExecutor)
        val step = createPluginStep()

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = observingStore)) {
                val result =
                    step.execute(
                        workflowName = workflowName,
                        state = "init",
                        context = WorkflowContext(workflowId = workflowId),
                        executorResolver = resolver,
                    )
                assertThat(result).isEqualTo("init:ok")
                assertThat(observedStates).containsExactly(
                    WorkflowEffectEvidenceState.ADMITTED,
                    WorkflowEffectEvidenceState.DISPATCHED,
                    WorkflowEffectEvidenceState.COMPLETED,
                )

                val record = delegateStore.listEvidence(workflowName, workflowId).single()
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                assertThat(record.completedAtEpochMillis).isNotNull()
                assertThat(record.diagnosticSummary).isNull()
            }
        }
    }

    @Test
    fun `EXT-08 - COMPLETED write fails Invocation not repeated, failure surfaced`() {
        val delegateStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val updateCount = AtomicInteger(0)
        val failingStore =
            object : WorkflowEffectEvidenceRecordStore by delegateStore {
                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                    val count = updateCount.incrementAndGet()
                    if (count == 2) {
                        // First update is DISPATCHED (count 1), second update is COMPLETED (count 2)
                        error("Database unavailable during COMPLETED write")
                    }
                    return delegateStore.updateEvidence(record)
                }
            }

        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-1")
            }
        val countingExecutor = CountingExecutor()
        val resolver = SimpleResolver(countingExecutor)
        val step = createPluginStep()

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = failingStore)) {
                val error =
                    assertFailsWith<ExternalEvidencePersistenceException> {
                        step.execute(
                            workflowName = workflowName,
                            state = "init",
                            context = WorkflowContext(workflowId = workflowId),
                            executorResolver = resolver,
                        )
                    }
                assertThat(error.cause).hasMessageContaining("Database unavailable during COMPLETED write")
                assertThat(countingExecutor.executions.get()).isEqualTo(1)

                val record = delegateStore.listEvidence(workflowName, workflowId).single()
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
                assertThat(record.diagnosticSummary).isEqualTo(EXTERNAL_EVIDENCE_FAILED_DIAGNOSTIC)
            }
        }
    }

    @Test
    fun `EXT-09 - Merge callback fails E8 remains COMPLETED`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-1")
            }
        val countingExecutor = CountingExecutor()
        val resolver = SimpleResolver(countingExecutor)
        val step =
            createPluginStep(
                merge = { _, _, _ ->
                    error("Merge logic failed post-execution")
                },
            )

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = evidenceStore)) {
                val error =
                    assertFailsWith<IllegalStateException> {
                        step.execute(
                            workflowName = workflowName,
                            state = "init",
                            context = WorkflowContext(workflowId = workflowId),
                            executorResolver = resolver,
                        )
                    }
                assertThat(error).hasMessage("Merge logic failed post-execution")
                assertThat(countingExecutor.executions.get()).isEqualTo(1)

                val record = evidenceStore.listEvidence(workflowName, workflowId).single()
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                assertThat(record.completedAtEpochMillis).isNotNull()
                assertThat(record.diagnosticSummary).isNull()
            }
        }
    }

    @Test
    fun `EXT-10 - Parent cancellation Original cancellation identity preserved`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-1")
            }
        val expectedCancellation = CancellationException("Executor cancelled by caller")
        val cancellingExecutor =
            ExternalStepExecutor {
                throw expectedCancellation
            }
        val resolver =
            object : ExternalStepExecutorResolver {
                override fun isRegistered(typeId: String): Boolean = true

                override fun registeredTypeIds(): Set<String> = setOf("test-plugin")

                override fun create(typeId: String): ExternalStepExecutor = cancellingExecutor
            }
        val step = createPluginStep()

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = evidenceStore)) {
                val thrown =
                    assertFailsWith<CancellationException> {
                        step.execute(
                            workflowName = workflowName,
                            state = "init",
                            context = WorkflowContext(workflowId = workflowId),
                            executorResolver = resolver,
                        )
                    }
                assertThat(thrown).isSameAs(expectedCancellation)

                val record = evidenceStore.listEvidence(workflowName, workflowId).single()
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
                assertThat(record.diagnosticSummary).isEqualTo(EXTERNAL_CANCELLED_DIAGNOSTIC)
                assertThat(record.completedAtEpochMillis).isNull()
            }
        }
    }

    @Test
    fun `EXT-11 - Multiple E8 effects in one attempt Distinct sequences, shared authoritative parent`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val seqCounter = AtomicLong(0L)
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                val seq = seqCounter.incrementAndGet()
                WorkflowExternalPermit(sName, externalEffectKind(typeId), seq, checkpointGeneration = "gen-multi")
            }
        val resolver = SimpleResolver(CountingExecutor())
        val step1 = createPluginStep(name = "plugin-1", type = "type-a")
        val step2 = createPluginStep(name = "plugin-2", type = "type-b")

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = evidenceStore)) {
                step1.execute(workflowName, "start", WorkflowContext(workflowId), resolver)
                step2.execute(workflowName, "start", WorkflowContext(workflowId), resolver)

                val records = evidenceStore.listEvidence(workflowName, workflowId)
                assertThat(records).hasSize(2)

                val r1 = records.first { it.stepName == "plugin-1" }
                val r2 = records.first { it.stepName == "plugin-2" }

                assertThat(r1.stepAttemptId).isEqualTo(attemptId)
                assertThat(r2.stepAttemptId).isEqualTo(attemptId)
                assertThat(r1.key.admissionSeq).isEqualTo(1L)
                assertThat(r2.key.admissionSeq).isEqualTo(2L)
                assertThat(r1.effectKind).isEqualTo(externalEffectKind("type-a"))
                assertThat(r2.effectKind).isEqualTo(externalEffectKind("type-b"))
                assertThat(r1.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                assertThat(r2.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            }
        }
    }

    @Test
    fun `EXT-12 - Child coroutine execution inherits parent attempt context and generation`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-nested")
            }
        val resolver = SimpleResolver(CountingExecutor())
        val childStep = createPluginStep(name = "child-plugin")

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = evidenceStore)) {
                // Proves that a plugin step invoked within a child coroutine inherits the parent
                // attempt and admission scope from the outer coroutine context, recording evidence
                // with that parent lineage. (Nested BranchWorkflowStep branching is governed at
                // the step-orchestration boundary).
                launch {
                    childStep.execute(workflowName, "start", WorkflowContext(workflowId), resolver)
                }.join()

                val record = evidenceStore.listEvidence(workflowName, workflowId).single()
                assertThat(record.stepAttemptId).isEqualTo(attemptId)
                assertThat(record.key.checkpointGeneration).isEqualTo("gen-nested")
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            }
        }
    }

    @Test
    fun `EXT-13 - Sequential step attempts produce distinct evidence records and generations`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val admissionSeq = AtomicLong(0L)
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                val seq = admissionSeq.incrementAndGet()
                val gen = if (seq == 1L) "gen-1" else "gen-2"
                WorkflowExternalPermit(sName, externalEffectKind(typeId), seq, checkpointGeneration = gen)
            }

        // Attempt 1 fails
        val failExecutor = CountingExecutor(failOnExecute = RuntimeException("Attempt 1 network error"))
        val resolver1 = SimpleResolver(failExecutor)
        val step = createPluginStep()

        runBlocking {
            // Proves distinct evidence attribution when the context changes across sequential step attempts;
            // retry decision and recovery coordinator machinery are audited in supervisor integration suites.
            withContext(
                testContext(
                    admission = admission,
                    evidenceStore = evidenceStore,
                    attempt = PersistedStepAttempt(workflowName, workflowId, stepName, "attempt-1"),
                ),
            ) {
                assertFailsWith<RuntimeException> {
                    step.execute(workflowName, "start", WorkflowContext(workflowId), resolver1)
                }
            }

            // Attempt 2 succeeds
            val okExecutor = CountingExecutor()
            val resolver2 = SimpleResolver(okExecutor)
            withContext(
                testContext(
                    admission = admission,
                    evidenceStore = evidenceStore,
                    attempt = PersistedStepAttempt(workflowName, workflowId, stepName, "attempt-2"),
                ),
            ) {
                step.execute(workflowName, "start", WorkflowContext(workflowId), resolver2)
            }

            val records = evidenceStore.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)

            val r1 = records.first { it.stepAttemptId == "attempt-1" }
            assertThat(r1.key.checkpointGeneration).isEqualTo("gen-1")
            assertThat(r1.key.admissionSeq).isEqualTo(1L)
            assertThat(r1.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(r1.diagnosticSummary).isEqualTo(EXTERNAL_EXECUTION_FAILED_DIAGNOSTIC)

            val r2 = records.first { it.stepAttemptId == "attempt-2" }
            assertThat(r2.key.checkpointGeneration).isEqualTo("gen-2")
            assertThat(r2.key.admissionSeq).isEqualTo(2L)
            assertThat(r2.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(r2.diagnosticSummary).isNull()
        }
    }

    @Test
    fun `EXT-14 - Ungoverned workflow No evidence enforcement or writes`() {
        val countingExecutor = CountingExecutor()
        val resolver = SimpleResolver(countingExecutor)
        val step = createPluginStep()

        runBlocking {
            // No governed context elements installed
            val result =
                step.execute(
                    workflowName = workflowName,
                    state = "plain",
                    context = WorkflowContext(workflowId = "ungoverned-run"),
                    executorResolver = resolver,
                )
            assertThat(result).isEqualTo("plain:ok")
            assertThat(countingExecutor.executions.get()).isEqualTo(1)
        }
    }

    @Test
    fun `EXT-15 - Unknown plugin type No pre-admission resolver callback`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val permitAdmitted = AtomicBoolean(false)
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                permitAdmitted.set(true)
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-1")
            }
        val emptyResolver =
            object : ExternalStepExecutorResolver {
                val isRegisteredCalls = AtomicInteger(0)
                val createCalls = AtomicInteger(0)

                override fun isRegistered(typeId: String): Boolean {
                    isRegisteredCalls.incrementAndGet()
                    return false
                }

                override fun registeredTypeIds(): Set<String> = emptySet()

                override fun create(typeId: String): ExternalStepExecutor {
                    createCalls.incrementAndGet()
                    throw ExternalStepExecutorNotRegisteredException(typeId)
                }
            }
        val step = createPluginStep(type = "unregistered-type")

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = evidenceStore)) {
                val error =
                    assertFailsWith<ExternalStepExecutorNotRegisteredException> {
                        step.execute(
                            workflowName = workflowName,
                            state = "start",
                            context = WorkflowContext(workflowId = workflowId),
                            executorResolver = emptyResolver,
                        )
                    }
                assertThat(error.message).contains("unregistered-type")
                // Core invariant: no resolver callback runs before admission!
                assertThat(permitAdmitted.get()).isTrue()
                assertThat(emptyResolver.isRegisteredCalls.get()).isEqualTo(0)
                assertThat(emptyResolver.createCalls.get()).isEqualTo(1)

                val record = evidenceStore.listEvidence(workflowName, workflowId).single()
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
                assertThat(record.diagnosticSummary).isEqualTo(EXTERNAL_RESOLUTION_FAILED_DIAGNOSTIC)
            }
        }
    }

    @Test
    fun `EXT-16 - Sensitive-data injection No secrets in stored evidence`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-secret")
            }
        val secretApiKey = "sk-live-super-secret-key-12345"
        val secretPassword = "DATABASE_PASSWORD_SUPER_SECRET"
        val failingExecutor =
            CountingExecutor(failOnExecute = RuntimeException("Database authentication failed with password: $secretPassword"))
        val resolver = SimpleResolver(failingExecutor)
        val step =
            createPluginStep(
                config = mapOf("apiKey" to secretApiKey, "nested" to mapOf("secret" to "very-secret")),
            )

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = evidenceStore)) {
                assertFailsWith<RuntimeException> {
                    step.execute(
                        workflowName = workflowName,
                        state = "start",
                        context = WorkflowContext(workflowId = workflowId),
                        executorResolver = resolver,
                    )
                }

                val record = evidenceStore.listEvidence(workflowName, workflowId).single()
                assertThat(record.diagnosticSummary).isEqualTo(EXTERNAL_EXECUTION_FAILED_DIAGNOSTIC)
                assertThat(record.diagnosticSummary).doesNotContain(secretApiKey)
                assertThat(record.diagnosticSummary).doesNotContain(secretPassword)
            }
        }
    }

    @Test
    fun `EXT-17 - Missing child evidence lookup Fail closed`() {
        val storeWithMissingRecord =
            object : WorkflowEffectEvidenceRecordStore {
                override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord = record

                // Returns null for lookup
                override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? = null

                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord = record

                override suspend fun listEvidence(
                    workflowName: String,
                    workflowId: String,
                ): List<WorkflowEffectEvidenceRecord> = emptyList()
            }

        val admission =
            WorkflowExternalAdmission { sName, typeId ->
                WorkflowExternalPermit(sName, externalEffectKind(typeId), 1L, checkpointGeneration = "gen-1")
            }
        val resolver = SimpleResolver(CountingExecutor())
        val step = createPluginStep()

        runBlocking {
            withContext(testContext(admission = admission, evidenceStore = storeWithMissingRecord)) {
                val error =
                    assertFailsWith<WorkflowEvidenceResolutionException> {
                        step.execute(
                            workflowName = workflowName,
                            state = "init",
                            context = WorkflowContext(workflowId = workflowId),
                            executorResolver = resolver,
                        )
                    }
                assertThat(error.message).contains("Missing expected evidence record for key")
            }
        }
    }

    @Test
    fun `EXT-18 - Duplicate permit consumption Second invocation refused`() {
        val permit = WorkflowExternalPermit(stepName, externalEffectKind("test-plugin"), 1L, checkpointGeneration = "gen-1")
        assertTrue(permit.tryConsume(), "First consumption must succeed")

        val error =
            assertFailsWith<IllegalStateException> {
                permit.tryConsume()
            }
        assertThat(error.message).contains("was already consumed; one permit authorizes at most one dispatch")
    }

    @Test
    fun `EXT-19 - Persistence exception createCopy retains cause and suppressed across coroutine boundary`() {
        val rootCause = IllegalStateException("database connection reset")
        val suppressedDiagnostic = RuntimeException("correlation id context")
        val original =
            ExternalEvidencePersistenceException(rootCause).apply {
                addSuppressed(suppressedDiagnostic)
            }

        // Direct createCopy() verification
        val copy = original.createCopy()
        assertThat(copy).isNotNull()
        assertThat(copy.cause).isSameAs(rootCause)
        assertThat(copy.message).isEqualTo("External evidence persistence failed")
        assertThat(copy.suppressed).containsExactly(suppressedDiagnostic)

        // Coroutine stack-trace recovery boundary verification
        runBlocking {
            kotlinx.coroutines.supervisorScope {
                val thrown =
                    assertFailsWith<ExternalEvidencePersistenceException> {
                        async(Dispatchers.Default) {
                            delay(10)
                            throw original
                        }.await()
                    }
                assertThat(thrown.cause).isSameAs(rootCause)
                assertThat(thrown.suppressed).contains(suppressedDiagnostic)
            }
        }
    }

    private suspend fun saveGovernedInitialCheckpoint(fixture: JdbcWorkerFixture): WorkflowCheckpoint =
        fixture.checkpointStore.save(
            WorkflowCheckpoint(
                workflowName = workflowName,
                workflowId = workflowId,
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                statePayload = "start",
                metadata = fixture.workflow.checkpointMetadata() + encodeGovernedRunAttribution(GOVERNED_IDENTITY),
            ),
        )

    private fun createJdbcWorkerFixture(
        pool: JdbcConnectionPool,
        evidenceStoreOverride: WorkflowEffectEvidenceRecordStore = JdbcWorkflowEffectEvidenceRecordStore(pool),
        schemaStore: JdbcWorkflowEffectEvidenceRecordStore,
        executor: ExternalStepExecutor = ExternalStepExecutor { spec -> mapOf("output" to "done") },
    ): JdbcWorkerFixture {
        val checkpointStore = JdbcWorkflowCheckpointStore(pool)
        val leaseStore = JdbcWorkflowLeaseStore(pool)
        val attemptStore = JdbcStepAttemptRecordStore(pool)
        checkpointStore.evidenceStore = evidenceStoreOverride

        pool.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val statements =
                    listOf(checkpointStore.createTableSql(), leaseStore.createTableSql()) +
                        attemptStore.createSchemaSql() +
                        schemaStore.createSchemaSql()
                statements.forEach(stmt::execute)
            }
        }

        val resolver =
            object : ExternalStepExecutorResolver {
                override fun isRegistered(typeId: String): Boolean = typeId == "custom-plugin"

                override fun registeredTypeIds(): Set<String> = setOf("custom-plugin")

                override fun create(typeId: String): ExternalStepExecutor = executor
            }

        val wf =
            workflow<String>(workflowName) {
                pluginStep(
                    name = stepName,
                    type = "custom-plugin",
                    config = mapOf("param" to "test-val"),
                    merge = { state, result, _ -> "$state:${result["output"]}" },
                )
            }.build(externalStepExecutorResolver = resolver) { it }

        val workerConfig = WorkerConfig(workerId = "worker-e2e-1", poolName = "e2e-pool", pollIntervalMillis = 20)
        val persistence =
            WorkflowPersistence(
                checkpointStore = checkpointStore,
                stateCodec = PlainStringCodec,
                deleteCheckpointOnCompletion = false,
                evidenceStore = evidenceStoreOverride,
            )
        val supervisor =
            WorkflowExecutionSupervisor(
                config = workerConfig,
                leaseStore = leaseStore,
                checkpointStore = checkpointStore,
                stepAttemptStore = attemptStore,
                workflowBindings = WorkflowBindingRegistry { bind(wf, persistence) },
                observability = NoOpTramaiWorkerObserver,
                leaseCoordinator = LeaseCoordinator(workerConfig, leaseStore, NoOpTramaiWorkerObserver),
                recoveryCoordinator = WorkflowRecoveryCoordinator(leaseStore, attemptStore),
                leaseRenewalLoop = LeaseRenewalLoop(workerConfig, leaseStore, NoOpTramaiWorkerObserver),
                shuttingDownGracefully = { false },
                stepAttemptIdentitySource = StepAttemptIdentitySource { "attempt-e2e-1" },
            )
        return JdbcWorkerFixture(supervisor, checkpointStore, leaseStore, attemptStore, wf)
    }

    private data class JdbcWorkerFixture(
        val supervisor: WorkflowExecutionSupervisor,
        val checkpointStore: JdbcWorkflowCheckpointStore,
        val leaseStore: JdbcWorkflowLeaseStore,
        val attemptStore: JdbcStepAttemptRecordStore,
        val workflow: Workflow<String, String>,
    )

    private object PlainStringCodec : WorkflowStateCodec<String> {
        override fun encode(state: String): String = state

        override fun decode(payload: String): String = payload
    }

    private class CountingExecutor(
        val failOnExecute: Throwable? = null,
    ) : ExternalStepExecutor {
        val creates = AtomicInteger(0)
        val executions = AtomicInteger(0)

        override suspend fun execute(spec: Map<String, Any?>): Map<String, Any?> {
            executions.incrementAndGet()
            if (failOnExecute != null) {
                throw failOnExecute
            }
            return spec + ("output" to "ok")
        }
    }

    private class SimpleResolver(
        val counter: CountingExecutor,
    ) : ExternalStepExecutorResolver {
        override fun isRegistered(typeId: String): Boolean = true

        override fun registeredTypeIds(): Set<String> = setOf("test-plugin")

        override fun create(typeId: String): ExternalStepExecutor {
            counter.creates.incrementAndGet()
            return counter
        }
    }

    companion object {
        private val GOVERNED_IDENTITY =
            GovernedRunIdentity(
                deployment =
                    WorkloadDeploymentIdentity(
                        workloadId = WorkloadId("d7-workload"),
                        configuration =
                            WorkloadConfigurationIdentity(ConfigurationId("conf"), ConfigurationVersion("1")),
                        environmentId = EnvironmentId("test-env"),
                        deploymentId = DeploymentId("test-dep"),
                    ),
                runId = RunId("run-ext-1"),
            )
    }
}
