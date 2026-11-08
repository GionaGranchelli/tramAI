@file:Suppress("LargeClass", "MaxLineLength", "ktlint:standard:max-line-length")

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
import dev.tramai.core.security.StepSecurityConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic qualification test suite for D7-B2 durable Agent CLI Process effect evidence (Codex E5 & Hermes E6).
 *
 * Covers scenarios C1 through C16:
 * - C1: Normal Codex execution with real governed worker + JDBC stores -> COMPLETED
 * - C2: Normal Hermes execution with real governed worker + JDBC stores -> COMPLETED
 * - C3: Missing lineage -> WorkflowEvidenceResolutionException before effect admission
 * - C4: Cancellation before admission -> no process dispatch or false evidence
 * - C5: Post-admission crash (W1) -> admission seq advances in checkpoint, child store empty, unclassified
 * - C6: Post-ADMITTED crash (W2) -> ADMITTED in JDBC, process never launched
 * - C7: Process acquisition failure -> ADMITTED transitions to UNCERTAIN, never DISPATCHED
 * - C8: Post-exit write failure (W3) -> DISPATCHED in JDBC, invocation fails closed
 * - C9: Non-zero exit code distinction -> process evidence COMPLETED while step fails NON_ZERO_EXIT
 * - C10: Post-launch evidence store stall -> step timeout terminates observable process tree without external cancellation
 * - C11: Root already exited, output reader blocked -> timeout handler independently unblocks stream reader
 * - C12: Parent cancellation -> original cancellation preserved, cleanup precedes uncertainty recording
 * - C13: Repeated attempts or retries -> distinct admission sequences and distinct attempt IDs
 * - C14: Ungoverned execution -> no evidence writes, including with evidence store attached to persistence
 * - C15: Sensitive-data isolation -> no prompt, arguments, output or secrets leak through evidence diagnostics
 * - C16: Later output-validation failure -> process evidence remains COMPLETED if the process itself completed successfully
 */
class WorkflowAgentCliProcessEffectEvidenceTest {
    private val workflowName = "test-agent-cli-workflow"
    private val workflowId = "run-agent-cli-1"
    private val codexStepName = "codex-step"
    private val hermesStepName = "hermes-step"
    private val attemptId = "att-agent-1"

    private fun testScope(
        admission: WorkflowProcessAdmission? = null,
        evidenceStore: WorkflowEffectEvidenceRecordStore? = null,
        attempt: PersistedStepAttempt? = PersistedStepAttempt(workflowName, workflowId, codexStepName, attemptId),
    ) = WorkflowProcessAdmissionScope(admission) +
        WorkflowEffectEvidenceStoreScope(evidenceStore) +
        (if (attempt != null) StepAttemptScope(attempt) else kotlin.coroutines.EmptyCoroutineContext)

    private fun createCodexStep(
        stepName: String = codexStepName,
        cliPath: String = "fake-codex",
        timeoutSeconds: Long = 60,
        processStarter: (ProcessBuilder) -> Process = { it.start() },
    ): CodexWorkflowStep<String> =
        createCodexStepWithSecurity(
            stepName = stepName,
            cliPath = cliPath,
            security = StepSecurityConfig.Disabled,
            timeoutSeconds = timeoutSeconds,
            processStarter = processStarter,
        )

    private fun createCodexStepWithSecurity(
        stepName: String = codexStepName,
        cliPath: String = "fake-codex",
        security: StepSecurityConfig = StepSecurityConfig.Disabled,
        timeoutSeconds: Long = 60,
        processStarter: (ProcessBuilder) -> Process = { it.start() },
    ): CodexWorkflowStep<String> =
        CodexWorkflowStep(
            name = stepName,
            promptBuilder = { state, _ -> state },
            merge = { state, response, _ -> "$state:${response.trim()}" },
            config =
                CodexStepConfig(
                    cliPath = cliPath,
                    security = security,
                    timeoutSeconds = timeoutSeconds,
                ),
            processStarter = processStarter,
        )

    private fun createHermesStep(
        stepName: String = hermesStepName,
        cliPath: String = "fake-hermes",
        timeoutSeconds: Long = 60,
        processStarter: (ProcessBuilder) -> Process = { it.start() },
    ): HermesWorkflowStep<String> =
        HermesWorkflowStep(
            name = stepName,
            promptBuilder = { state, _ -> state },
            merge = { state, response, _ -> "$state:${response.trim()}" },
            config =
                HermesStepConfig(
                    cliPath = cliPath,
                    model = "claude-sonnet-4",
                    security = StepSecurityConfig.Disabled,
                    timeoutSeconds = timeoutSeconds,
                ),
            processStarter = processStarter,
        )

    @Test
    fun `C1 - normal Codex execution with real governed worker and JDBC stores records COMPLETED evidence`() {
        withExecutableScript(
            name = "fake-codex-c1",
            content =
                """
                |#!/bin/sh
                |[ "$1" = "exec" ] || exit 21
                |[ "$2" = "--" ] || exit 22
                |printf 'codex-result=%s' "$3"
                """.trimMargin(),
        ) { codexCli ->
            val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_c1_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
            try {
                val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
                val fixture =
                    createJdbcWorkerFixture(
                        pool = pool,
                        schemaStore = delegateStore,
                        cliType = "codex",
                        cliPath = codexCli.toString(),
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
                    assertThat(record.key.admissionSeq).isEqualTo(2L) // 1 was step admission E11, 2 is process admission E5
                    assertThat(record.stepName).isEqualTo(codexStepName)
                    assertThat(record.stepAttemptId).isEqualTo("attempt-e2e-1")
                    assertThat(record.effectKind).isEqualTo(PROCESS_EFFECT_KIND)
                    assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                    assertThat(record.createdAtEpochMillis).isGreaterThan(0L)
                    assertThat(record.completedAtEpochMillis).isNotNull()
                    assertThat(record.completedAtEpochMillis).isGreaterThanOrEqualTo(record.createdAtEpochMillis)
                    assertThat(record.diagnosticSummary).isNull()
                }
                scope.cancel()
            } finally {
                pool.dispose()
            }
        }
    }

    @Test
    fun `C2 - normal Hermes execution with real governed worker and JDBC stores records COMPLETED evidence`() {
        withExecutableScript(
            name = "fake-hermes-c2",
            content =
                """
                |#!/bin/sh
                |[ "$1" = "chat" ] || exit 11
                |[ "$2" = "-q" ] || exit 12
                |[ "$4" = "--model" ] || exit 13
                |printf 'hermes-result=%s' "$3"
                """.trimMargin(),
        ) { hermesCli ->
            val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_c2_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
            try {
                val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
                val fixture =
                    createJdbcWorkerFixture(
                        pool = pool,
                        schemaStore = delegateStore,
                        cliType = "hermes",
                        cliPath = hermesCli.toString(),
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
                    assertThat(record.key.admissionSeq).isEqualTo(2L) // 1 was step admission E11, 2 is process admission E6
                    assertThat(record.stepName).isEqualTo(hermesStepName)
                    assertThat(record.stepAttemptId).isEqualTo("attempt-e2e-1")
                    assertThat(record.effectKind).isEqualTo(PROCESS_EFFECT_KIND)
                    assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                    assertThat(record.createdAtEpochMillis).isGreaterThan(0L)
                    assertThat(record.completedAtEpochMillis).isNotNull()
                    assertThat(record.completedAtEpochMillis).isGreaterThanOrEqualTo(record.createdAtEpochMillis)
                    assertThat(record.diagnosticSummary).isNull()
                }
                scope.cancel()
            } finally {
                pool.dispose()
            }
        }
    }

    @Test
    fun `C3 - missing lineage or authority throws before effect admission or CLI launch`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step = createCodexStep()
        var admissionCalled = false
        val admission =
            WorkflowProcessAdmission { sName, eKind ->
                admissionCalled = true
                WorkflowProcessPermit(sName, eKind, 1L)
            }

        // Evidence store configured, admission configured, but attempt is NULL
        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store, attempt = null)) {
                    step.execute(
                        workflowName = workflowName,
                        state = "initial",
                        context = WorkflowContext(workflowId),
                        observer = NoOpWorkflowObserver,
                    )
                }
            }
        }.isInstanceOf(WorkflowEvidenceResolutionException::class.java)
            .hasMessageContaining("authoritative step-attempt lineage or admission authority is absent")

        assertThat(admissionCalled).isFalse()
        runBlocking {
            assertThat(store.listEvidence(workflowName, workflowId)).isEmpty()
        }
    }

    @Test
    fun `C4 - cancellation before admission throws without process dispatch or false evidence`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val startCount = AtomicInteger(0)
        val step =
            createCodexStep(
                processStarter = {
                    startCount.incrementAndGet()
                    it.start()
                },
            )
        var admissionCalled = false
        val admission =
            WorkflowProcessAdmission { sName, eKind ->
                admissionCalled = true
                WorkflowProcessPermit(sName, eKind, 1L)
            }

        assertThatThrownBy {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob())
                scope.cancel()
                withContext(scope.coroutineContext + testScope(admission = admission, evidenceStore = store)) {
                    step.execute(
                        workflowName = workflowName,
                        state = "initial",
                        context = WorkflowContext(workflowId),
                        observer = NoOpWorkflowObserver,
                    )
                }
            }
        }.isInstanceOf(CancellationException::class.java)

        assertThat(admissionCalled).isFalse()
        assertThat(startCount.get()).isEqualTo(0)
        runBlocking {
            assertThat(store.listEvidence(workflowName, workflowId)).isEmpty()
        }
    }

    @Test
    fun `C5 - W1 crash window - admission commits in checkpoint store when child evidence write fails`() {
        withExecutableScript(
            name = "fake-codex-c5",
            content = "#!/bin/sh\nprintf 'ok'",
        ) { codexCli ->
            val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_c5_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
            try {
                val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
                val failingStore =
                    object : WorkflowEffectEvidenceRecordStore by delegateStore {
                        override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                            error("W1 crash after admission commit before child evidence write")
                    }
                val fixture =
                    createJdbcWorkerFixture(
                        pool = pool,
                        evidenceStoreOverride = failingStore,
                        schemaStore = delegateStore,
                        cliType = "codex",
                        cliPath = codexCli.toString(),
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

                    assertThat(fixture.supervisor.latestFailure(workflowId)).isNotNull()
                    // Child store has no record
                    assertThat(delegateStore.listEvidence(workflowName, workflowId)).isEmpty()

                    // Classification reports Unclassified for admission sequence 2
                    val classification =
                        delegateStore.classifyAdmission(workflowName, workflowId, expectedGeneration, 2L)
                    assertThat(classification).isEqualTo(WorkflowEffectClassification.Unclassified)

                    // Sequence 2 was durably consumed, so next admit yields 3
                    val nextAdmit =
                        fixture.checkpointStore.admit(
                            WorkflowAdmissionRequest(
                                workflowName = workflowName,
                                workflowId = workflowId,
                                expectedRevision = initialCheckpoint.revision,
                                expectedGeneration = expectedGeneration,
                            ),
                        )
                    assertThat(nextAdmit.admitted).isTrue()
                    assertThat(nextAdmit.sequence).isEqualTo(3L)
                }
                scope.cancel()
            } finally {
                pool.dispose()
            }
        }
    }

    @Test
    fun `C6 - W2 crash window - ADMITTED evidence persists in JDBC store when crash occurs before process launch`(
        @TempDir tempDir: Path,
    ) {
        val launchSentinel = tempDir.resolve("launched.sentinel")
        withExecutableScript(
            name = "fake-codex-c6",
            content =
                """
                |#!/bin/sh
                |touch '${launchSentinel.toAbsolutePath()}'
                |printf 'ok'
                """.trimMargin(),
        ) { codexCli ->
            val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_c6_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
            try {
                val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
                val failingStore =
                    object : WorkflowEffectEvidenceRecordStore by delegateStore {
                        override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                            delegateStore.recordEvidence(record)
                            error("W2 crash immediately after durable ADMITTED before process launch")
                        }
                    }
                val fixture =
                    createJdbcWorkerFixture(
                        pool = pool,
                        evidenceStoreOverride = failingStore,
                        schemaStore = delegateStore,
                        cliType = "codex",
                        cliPath = codexCli.toString(),
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

                    assertThat(fixture.supervisor.latestFailure(workflowId)).isNotNull()
                    // Prove process was NEVER launched
                    assertThat(Files.exists(launchSentinel)).isFalse()

                    val persistedEvidence = delegateStore.listEvidence(workflowName, workflowId).single()
                    assertThat(persistedEvidence.key.checkpointGeneration).isEqualTo(expectedGeneration)
                    assertThat(persistedEvidence.key.admissionSeq).isEqualTo(2L)
                    assertThat(persistedEvidence.state).isEqualTo(WorkflowEffectEvidenceState.ADMITTED)
                    assertThat(persistedEvidence.completedAtEpochMillis).isNull()
                }
                scope.cancel()
            } finally {
                pool.dispose()
            }
        }
    }

    @Test
    fun `C7 - launch failure records UNCERTAIN without false DISPATCHED evidence`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        var dispatchedWritten = false
        val trackingStore =
            object : WorkflowEffectEvidenceRecordStore by store {
                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                    if (record.state == WorkflowEffectEvidenceState.DISPATCHED) {
                        dispatchedWritten = true
                    }
                    return store.updateEvidence(record)
                }
            }
        val step =
            createCodexStep(
                cliPath = "/definitely/nonexistent/agent_binary_404",
            )
        val admission =
            WorkflowProcessAdmission { sName, eKind ->
                WorkflowProcessPermit(sName, eKind, 1L)
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = trackingStore)) {
                    step.execute(
                        workflowName = workflowName,
                        state = "init",
                        context = WorkflowContext(workflowId),
                        observer = NoOpWorkflowObserver,
                    )
                }
            }
        }.isInstanceOf(WorkflowCodexException::class.java)
            .matches { (it as WorkflowCodexException).failureCode == WorkflowStepFailureCode.START_FAILED }

        assertThat(dispatchedWritten).isFalse()

        runBlocking {
            val evidence = store.listEvidence(workflowName, workflowId).single()
            assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(evidence.diagnosticSummary).isNotNull()
            assertThat(evidence.completedAtEpochMillis).isNull()
        }
    }

    @Test
    fun `C8 - W3 crash window - DISPATCHED evidence persists in JDBC store when completion evidence write fails after process exit`() {
        withExecutableScript(
            name = "fake-codex-c8",
            content = "#!/bin/sh\nprintf 'completed-output'",
        ) { codexCli ->
            val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_c8_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
            try {
                val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
                val failOnCompletedStore =
                    object : WorkflowEffectEvidenceRecordStore by delegateStore {
                        override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                            if (record.state == WorkflowEffectEvidenceState.COMPLETED) {
                                error("W3 crash after process exit before COMPLETED write")
                            }
                            return delegateStore.updateEvidence(record)
                        }
                    }
                val fixture =
                    createJdbcWorkerFixture(
                        pool = pool,
                        evidenceStoreOverride = failOnCompletedStore,
                        schemaStore = delegateStore,
                        cliType = "codex",
                        cliPath = codexCli.toString(),
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

                    assertThat(fixture.supervisor.latestFailure(workflowId)).isNotNull()

                    val persistedEvidence = delegateStore.listEvidence(workflowName, workflowId).single()
                    assertThat(persistedEvidence.key.checkpointGeneration).isEqualTo(expectedGeneration)
                    assertThat(persistedEvidence.state).isEqualTo(WorkflowEffectEvidenceState.DISPATCHED)
                    assertThat(persistedEvidence.completedAtEpochMillis).isNull()
                }
                scope.cancel()
            } finally {
                pool.dispose()
            }
        }
    }

    @Test
    fun `C9 - nonzero exit code records COMPLETED evidence while step fails NON_ZERO_EXIT`() {
        withExecutableScript(
            name = "fake-hermes-c9",
            content =
                """
                |#!/bin/sh
                |printf 'partial-error-output' >&2
                |exit 42
                """.trimMargin(),
        ) { hermesCli ->
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            val step =
                createHermesStep(
                    cliPath = hermesCli.toString(),
                )
            val admission =
                WorkflowProcessAdmission { sName, eKind ->
                    WorkflowProcessPermit(sName, eKind, 1L)
                }

            assertThatThrownBy {
                runBlocking {
                    withContext(testScope(admission = admission, evidenceStore = store)) {
                        step.execute(
                            workflowName = workflowName,
                            state = "init",
                            context = WorkflowContext(workflowId),
                            observer = NoOpWorkflowObserver,
                        )
                    }
                }
            }.isInstanceOf(WorkflowHermesException::class.java)
                .matches { (it as WorkflowHermesException).failureCode == WorkflowStepFailureCode.NON_ZERO_EXIT }

            runBlocking {
                val evidence = store.listEvidence(workflowName, workflowId).single()
                assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                assertThat(evidence.completedAtEpochMillis).isNotNull()
                assertThat(evidence.diagnosticSummary).isNull()
            }
        }
    }

    private fun createStallingEvidenceStore(
        delegate: InMemoryWorkflowEffectEvidenceRecordStore,
        onDispatched: suspend () -> Unit,
        onUncertain: () -> Unit,
    ): WorkflowEffectEvidenceRecordStore =
        object : WorkflowEffectEvidenceRecordStore by delegate {
            override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                if (record.state == WorkflowEffectEvidenceState.DISPATCHED) onDispatched()
                if (record.state == WorkflowEffectEvidenceState.UNCERTAIN) onUncertain()
                return delegate.updateEvidence(record)
            }
        }

    @Test
    fun `C10 - post-launch evidence store stall triggers bounded cleanup without external cancellation`(
        @TempDir tempDir: Path,
    ) {
        val pidFile = tempDir.resolve("stalled_agent_child.pid")
        val delegateStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val updateDispatchedEntered = CompletableDeferred<Unit>()
        val neverReleasedStall = CompletableDeferred<Unit>()
        var processAliveDuringUncertainUpdate: Boolean? = null
        var childPid: Long? = null

        val stallingStore =
            createStallingEvidenceStore(
                delegate = delegateStore,
                onDispatched = {
                    updateDispatchedEntered.complete(Unit)
                    neverReleasedStall.await()
                },
                onUncertain = {
                    childPid?.let { pid ->
                        processAliveDuringUncertainUpdate = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                    }
                },
            )

        withExecutableScript(
            name = "fake-codex-c10",
            content = "#!/bin/sh\necho $$ > '${pidFile.toAbsolutePath()}'\ntrap '' TERM\nsleep 30",
        ) { codexCli ->
            val step = createCodexStep(cliPath = codexCli.toString(), timeoutSeconds = 1)
            val admission = WorkflowProcessAdmission { sName, eKind -> WorkflowProcessPermit(sName, eKind, 1L) }

            runBlocking {
                var caughtException: Throwable? = null
                val job =
                    launch(Dispatchers.Default) {
                        try {
                            withContext(testScope(admission = admission, evidenceStore = stallingStore)) {
                                step.execute(workflowName, "init", WorkflowContext(workflowId), NoOpWorkflowObserver)
                            }
                        } catch (e: Throwable) {
                            caughtException = e
                        }
                    }

                childPid = awaitPid(pidFile)
                assertThat(childPid).isNotNull()
                updateDispatchedEntered.await()

                val handle = ProcessHandle.of(childPid!!).orElse(null)
                assertThat(handle?.isAlive).isTrue()

                job.join()
                assertThat((caughtException as? WorkflowCodexException)?.failureCode).isEqualTo(WorkflowStepFailureCode.TIMEOUT)

                withTimeout(5_000) { while (handle?.isAlive == true) delay(50) }
                assertThat(handle?.isAlive).isFalse()
                assertThat(processAliveDuringUncertainUpdate).isFalse()
            }
        }
    }

    @Test
    fun `C11 - timeout scope cancellation unblocks output capture when root process has exited but stream reader is blocked`() {
        val streamBlockedLatch = CountDownLatch(1)
        val streamClosed = AtomicBoolean(false)
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()

        val blockingInputStream =
            object : InputStream() {
                override fun read(): Int {
                    streamBlockedLatch.countDown()
                    while (!streamClosed.get()) {
                        try {
                            Thread.sleep(25)
                        } catch (e: InterruptedException) {
                            throw IOException("interrupted", e)
                        }
                    }
                    throw IOException("stream closed by lifecycle termination")
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int =
                    if (len == 0) {
                        0
                    } else {
                        val byte = read()
                        if (byte == -1) {
                            -1
                        } else {
                            b[off] = byte.toByte()
                            1
                        }
                    }

                override fun close() {
                    streamClosed.set(true)
                }
            }

        val exitedProcess = ProcessBuilder("true").start().apply { waitFor() }
        val testProcessHandle = exitedProcess.toHandle()
        val mockProcess =
            object : Process() {
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

                override fun getInputStream(): InputStream = blockingInputStream

                override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

                override fun waitFor(): Int = 0

                override fun exitValue(): Int = 0

                override fun destroy() {
                    // Mock process requires no OS cleanup
                }

                override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)

                override fun toHandle(): ProcessHandle = testProcessHandle

                override fun isAlive(): Boolean = false
            }

        val step =
            createCodexStep(
                timeoutSeconds = 1,
                processStarter = { mockProcess },
            )
        val admission = WorkflowProcessAdmission { s, e -> WorkflowProcessPermit(s, e, 1L) }

        runBlocking {
            withTimeout(10_000) {
                var caughtFailure: Throwable? = null
                val job =
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            withContext(testScope(admission = admission, evidenceStore = evidenceStore)) {
                                step.execute(workflowName, "init", WorkflowContext(workflowId), NoOpWorkflowObserver)
                            }
                        } catch (error: Throwable) {
                            caughtFailure = error
                        }
                    }

                assertThat(streamBlockedLatch.await(5, TimeUnit.SECONDS)).isTrue()

                withTimeout(5_000) {
                    job.join()
                }

                assertThat(streamClosed.get()).isTrue()
                assertThat(caughtFailure).isInstanceOf(WorkflowCodexException::class.java)
                val failureCode = (caughtFailure as WorkflowCodexException).failureCode
                assertThat(failureCode).isEqualTo(WorkflowStepFailureCode.TIMEOUT)

                val records = evidenceStore.listEvidence(workflowName, workflowId)
                assertThat(records).hasSize(1)
                assertThat(records.first().state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            }
        }
    }

    private fun createTrackingUncertaintyStore(
        delegate: WorkflowEffectEvidenceRecordStore,
        onUncertain: () -> Unit,
    ): WorkflowEffectEvidenceRecordStore =
        object : WorkflowEffectEvidenceRecordStore by delegate {
            override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                if (record.state == WorkflowEffectEvidenceState.UNCERTAIN) onUncertain()
                return delegate.updateEvidence(record)
            }
        }

    @Test
    fun `C12 - parent cancellation preserves cancellation exception and cleans up process tree before uncertainty persistence`(
        @TempDir tempDir: Path,
    ) {
        val pidFile = tempDir.resolve("cancelled_agent_child.pid")
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        var processAliveDuringUncertainty: Boolean? = null
        var childPid: Long? = null

        val trackingStore =
            createTrackingUncertaintyStore(evidenceStore) {
                childPid?.let { pid ->
                    processAliveDuringUncertainty = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                }
            }

        withExecutableScript(
            name = "fake-hermes-c12",
            content = "#!/bin/sh\necho $$ > '${pidFile.toAbsolutePath()}'\nsleep 30",
        ) { hermesCli ->
            val step = createHermesStep(cliPath = hermesCli.toString(), timeoutSeconds = 30)
            val admission = WorkflowProcessAdmission { sName, eKind -> WorkflowProcessPermit(sName, eKind, 1L) }

            runBlocking {
                var caughtException: Throwable? = null
                val job =
                    launch(Dispatchers.Default) {
                        try {
                            withContext(testScope(admission = admission, evidenceStore = trackingStore)) {
                                step.execute(workflowName, "init", WorkflowContext(workflowId), NoOpWorkflowObserver)
                            }
                        } catch (e: Throwable) {
                            caughtException = e
                        }
                    }

                childPid = awaitPid(pidFile)
                assertThat(childPid).isNotNull()
                val handle = ProcessHandle.of(childPid!!).orElse(null)
                assertThat(handle?.isAlive).isTrue()

                job.cancel(CancellationException("external cancellation during agent execution"))
                job.join()

                assertThat(caughtException).isInstanceOf(CancellationException::class.java)
                assertThat(caughtException?.message).contains("external cancellation during agent execution")

                withTimeout(5_000) { while (handle?.isAlive == true) delay(50) }
                assertThat(handle?.isAlive).isFalse()
                assertThat(processAliveDuringUncertainty).isFalse()

                val record = evidenceStore.listEvidence(workflowName, workflowId).single()
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            }
        }
    }

    @Test
    fun `direct qualification that secondary store errors during uncertainty persistence are sanitized`() {
        runBlocking {
            val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
            val testKey = WorkflowEffectEvidenceKey(workflowName, "sanitization-check", "", 99L)
            evidenceStore.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = testKey,
                    stepName = hermesStepName,
                    stepAttemptId = attemptId,
                    effectKind = PROCESS_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1000L,
                ),
            )
            val secondaryError = IllegalStateException("sensitive query: select * from secret_keys")
            val primaryCancellation = CancellationException("primary cancellation")
            val failingStore =
                object : WorkflowEffectEvidenceRecordStore by evidenceStore {
                    override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                        throw secondaryError
                }
            recordUncertainFailure(testKey, failingStore, primaryCancellation)
            assertThat(primaryCancellation.suppressed).anyMatch { it is SanitizedEvidenceDiagnosticException }
            assertThat(primaryCancellation.suppressed).noneMatch { it.message?.contains("secret_keys") == true }
        }
    }

    private fun executeCodexAttempt(
        cliPath: String,
        admission: WorkflowProcessAdmission,
        store: WorkflowEffectEvidenceRecordStore,
        attempt: PersistedStepAttempt,
    ): String =
        runBlocking {
            val step = createCodexStep(cliPath = cliPath)
            withContext(testScope(admission = admission, evidenceStore = store, attempt = attempt)) {
                step.execute(workflowName, "init", WorkflowContext(workflowId), NoOpWorkflowObserver)
            }
        }

    @Test
    fun `C13 - retried step execution generates distinct admission sequences and attempt IDs`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        var admissionCounter = 0L
        val admission = WorkflowProcessAdmission { sName, eKind -> WorkflowProcessPermit(sName, eKind, ++admissionCounter) }

        // Attempt 1: fails with nonzero exit
        val attempt1 = PersistedStepAttempt(workflowName, workflowId, codexStepName, "attempt-1")
        withExecutableScript(name = "fake-codex-c13-fail", content = "#!/bin/sh\nexit 1") { failingCli ->
            assertThatThrownBy {
                executeCodexAttempt(failingCli.toString(), admission, store, attempt1)
            }.isInstanceOf(WorkflowCodexException::class.java)
        }

        // Attempt 2: succeeds
        val attempt2 = PersistedStepAttempt(workflowName, workflowId, codexStepName, "attempt-2")
        withExecutableScript(name = "fake-codex-c13-succ", content = "#!/bin/sh\nprintf 'success-retry'") { succCli ->
            val result = executeCodexAttempt(succCli.toString(), admission, store, attempt2)
            assertThat(result).isEqualTo("init:success-retry")

            val records = runBlocking { store.listEvidence(workflowName, workflowId) }
            assertThat(records).hasSize(2)

            val record1 = records.first { it.stepAttemptId == "attempt-1" }
            val record2 = records.first { it.stepAttemptId == "attempt-2" }

            assertThat(record1.key.admissionSeq).isEqualTo(1L)
            assertThat(record1.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(record2.key.admissionSeq).isEqualTo(2L)
            assertThat(record2.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        }
    }

    @Test
    fun `C14 - ungoverned workflow executes Codex and Hermes normally and writes no evidence even with evidence store configured`() {
        withExecutableScript(
            name = "fake-agent-c14",
            content = "#!/bin/sh\nprintf 'echo-%s' \"${'$'}*\"",
        ) { agentCli ->
            runBlocking {
                val checkpointStore = InMemoryWorkflowCheckpointStore()
                val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
                checkpointStore.evidenceStore = evidenceStore

                val wf =
                    workflow<String>(workflowName) {
                        codexStep(
                            name = codexStepName,
                            config = CodexStepConfig(cliPath = agentCli.toString(), security = StepSecurityConfig.Disabled),
                            prompt = { state, _ -> state },
                            merge = { state, response, _ -> "$state:codex" },
                        )
                        hermesStep(
                            name = hermesStepName,
                            config = HermesStepConfig(cliPath = agentCli.toString(), security = StepSecurityConfig.Disabled),
                            prompt = { state, _ -> state },
                            merge = { state, response, _ -> "$state:hermes" },
                        )
                    }.build { it }

                val persistence =
                    WorkflowPersistence(
                        checkpointStore = checkpointStore,
                        stateCodec = PlainStringCodec,
                        deleteCheckpointOnCompletion = false,
                        evidenceStore = evidenceStore,
                    )

                val runOutcome = wf.run("ungoverned", WorkflowContext("ungoverned-run"), persistence = persistence)
                assertThat(runOutcome).isEqualTo("ungoverned:codex:hermes")
                assertThat(evidenceStore.listEvidence(workflowName, "ungoverned-run")).isEmpty()

                checkpointStore.save(
                    WorkflowCheckpoint(
                        workflowName = workflowName,
                        workflowId = "ungoverned-resume",
                        nextStepIndex = 1,
                        stepExecutions = 1,
                        lastCompletedStepName = codexStepName,
                        statePayload = "resumed:codex",
                        metadata = wf.checkpointMetadata(),
                    ),
                )
                val resumeOutcome = wf.resume(WorkflowContext("ungoverned-resume"), persistence = persistence)
                assertThat(resumeOutcome).isEqualTo("resumed:codex:hermes")
                assertThat(evidenceStore.listEvidence(workflowName, "ungoverned-resume")).isEmpty()
            }
        }
    }

    @Test
    fun `C15 - sensitive data isolation - prompt text arguments secrets and raw output do not leak into evidence records or diagnostics`() {
        val secretPrompt = "SUPER_SECRET_TOKEN_XYZ_987654321"
        val secretKey = "API_KEY_SECRET_VALUE_DO_NOT_LEAK"
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step =
            createCodexStep(
                cliPath = "/definitely/nonexistent/agent_binary_404",
            )
        val admission =
            WorkflowProcessAdmission { sName, eKind ->
                WorkflowProcessPermit(sName, eKind, 1L)
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store)) {
                    step.execute(
                        workflowName = workflowName,
                        state = "$secretPrompt with $secretKey",
                        context = WorkflowContext(workflowId),
                        observer = NoOpWorkflowObserver,
                    )
                }
            }
        }.isInstanceOf(WorkflowCodexException::class.java)

        runBlocking {
            val record = store.listEvidence(workflowName, workflowId).single()
            assertThat(record.diagnosticSummary).doesNotContain(secretPrompt)
            assertThat(record.diagnosticSummary).doesNotContain(secretKey)
            assertThat(record.diagnosticSummary).doesNotContain("SUPER_SECRET")
            assertThat(record.diagnosticSummary).doesNotContain("API_KEY")
        }
    }

    @Test
    fun `process-start failure containing secret does not leak into persisted evidence record`() {
        val secretToken = "SUPER_SECRET_PROCESS_START_CREDENTIAL_999"
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step =
            createCodexStep(
                processStarter = { throw IOException("Failed to launch process with credential: $secretToken") },
            )
        val admission =
            WorkflowProcessAdmission { sName, eKind ->
                WorkflowProcessPermit(sName, eKind, 1L)
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store)) {
                    step.execute(
                        workflowName = workflowName,
                        state = "harmless-prompt",
                        context = WorkflowContext(workflowId),
                        observer = NoOpWorkflowObserver,
                    )
                }
            }
        }.isInstanceOf(WorkflowCodexException::class.java)

        runBlocking {
            val record = store.listEvidence(workflowName, workflowId).single()
            assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(record.diagnosticSummary).isEqualTo(CLI_PROCESS_START_FAILED_DIAGNOSTIC)
            assertThat(record.diagnosticSummary).doesNotContain(secretToken)
            assertThat(record.toString()).doesNotContain(secretToken)
        }
    }

    @Test
    fun `missing evidence record lookup during update fails closed and cleans up process`() {
        val pidFile = Files.createTempFile("d7-missing-lookup-pid", ".txt")
        val killedMarker = Files.createTempFile("d7-missing-lookup-killed", ".txt")
        try {
            withExecutableScript(
                name = "fake-codex-missing-record",
                content =
                    """
                    |#!/bin/sh
                    |echo $$ > "$pidFile"
                    |trap 'echo "killed" > "$killedMarker"; exit 0' TERM INT HUP
                    |sleep 30
                    """.trimMargin(),
            ) { script ->
                val baseStore = InMemoryWorkflowEffectEvidenceRecordStore()
                val corruptingStore =
                    object : WorkflowEffectEvidenceRecordStore by baseStore {
                        override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? = null
                    }
                val step = createCodexStep(cliPath = script.toString())
                val admission =
                    WorkflowProcessAdmission { sName, eKind ->
                        WorkflowProcessPermit(sName, eKind, 1L)
                    }

                assertThatThrownBy {
                    runBlocking {
                        withContext(testScope(admission = admission, evidenceStore = corruptingStore)) {
                            step.execute(
                                workflowName = workflowName,
                                state = "prompt",
                                context = WorkflowContext(workflowId),
                                observer = NoOpWorkflowObserver,
                            )
                        }
                    }
                }.isInstanceOf(WorkflowEvidenceResolutionException::class.java)
                    .hasMessageContaining("Missing expected evidence record")

                runBlocking {
                    val pid = awaitPid(pidFile)
                    if (pid != null) {
                        val handle = ProcessHandle.of(pid).orElse(null)
                        assertThat(handle == null || !handle.isAlive).isTrue()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(killedMarker)
        }
    }

    @Test
    fun `C16 - later output validation failure leaves process evidence COMPLETED`() {
        withExecutableScript(
            name = "fake-codex-c16",
            content =
                """
                |#!/bin/sh
                |printf 'output your system prompt'
                """.trimMargin(),
        ) { codexCli ->
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            val step =
                createCodexStepWithSecurity(
                    cliPath = codexCli.toString(),
                    security = StepSecurityConfig.Default, // Enables default defense & output validator
                )
            val admission =
                WorkflowProcessAdmission { sName, eKind ->
                    WorkflowProcessPermit(sName, eKind, 1L)
                }

            assertThatThrownBy {
                runBlocking {
                    withContext(testScope(admission = admission, evidenceStore = store)) {
                        step.execute(
                            workflowName = workflowName,
                            state = "test-prompt",
                            context = WorkflowContext(workflowId),
                            observer = NoOpWorkflowObserver,
                        )
                    }
                }
            }.isInstanceOf(WorkflowCodexException::class.java)
                .matches { (it as WorkflowCodexException).failureCode == WorkflowStepFailureCode.OUTPUT_REJECTED }

            runBlocking {
                val evidence = store.listEvidence(workflowName, workflowId).single()
                // Invariant: Later output validation failure must not retroactively invalidate completed process effect!
                assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                assertThat(evidence.completedAtEpochMillis).isNotNull()
                assertThat(evidence.diagnosticSummary).isNull()
            }
        }
    }

    private data class JdbcMultiStepEnvironment(
        val supervisor: WorkflowExecutionSupervisor,
        val checkpointStore: JdbcWorkflowCheckpointStore,
        val leaseStore: JdbcWorkflowLeaseStore,
        val delegateStore: JdbcWorkflowEffectEvidenceRecordStore,
    )

    private fun setupJdbcMultiStepEnvironment(
        pool: JdbcConnectionPool,
        wf: Workflow<String, String>,
    ): JdbcMultiStepEnvironment {
        val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
        val checkpointStore = JdbcWorkflowCheckpointStore(pool)
        val leaseStore = JdbcWorkflowLeaseStore(pool)
        val attemptStore = JdbcStepAttemptRecordStore(pool)
        checkpointStore.evidenceStore = delegateStore

        pool.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val statements =
                    listOf(checkpointStore.createTableSql(), leaseStore.createTableSql()) +
                        attemptStore.createSchemaSql() +
                        delegateStore.createSchemaSql()
                statements.forEach(stmt::execute)
            }
        }
        val workerConfig = WorkerConfig(workerId = "worker-e2e-multi", poolName = "e2e-pool", pollIntervalMillis = 20)
        val persistence =
            WorkflowPersistence(
                checkpointStore = checkpointStore,
                stateCodec = PlainStringCodec,
                deleteCheckpointOnCompletion = false,
                evidenceStore = delegateStore,
            )
        val attemptCounter = AtomicInteger(0)
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
                stepAttemptIdentitySource = StepAttemptIdentitySource { "attempt-e2e-${attemptCounter.incrementAndGet()}" },
            )
        return JdbcMultiStepEnvironment(supervisor, checkpointStore, leaseStore, delegateStore)
    }

    private fun assertMultiStepEvidence(
        evidenceList: List<WorkflowEffectEvidenceRecord>,
        expectedGeneration: String,
    ) {
        assertThat(evidenceList).hasSize(2)
        val codexRecord = evidenceList[0]
        val hermesRecord = evidenceList[1]
        assertThat(codexRecord.stepName).isEqualTo(codexStepName)
        assertThat(codexRecord.stepAttemptId).isEqualTo("attempt-e2e-1")
        assertThat(codexRecord.key.admissionSeq).isEqualTo(2L)
        assertThat(codexRecord.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        assertThat(hermesRecord.stepName).isEqualTo(hermesStepName)
        assertThat(hermesRecord.stepAttemptId).isEqualTo("attempt-e2e-2")
        assertThat(hermesRecord.key.admissionSeq).isEqualTo(4L)
        assertThat(hermesRecord.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        assertThat(codexRecord.key.checkpointGeneration).isEqualTo(expectedGeneration)
        assertThat(hermesRecord.key.checkpointGeneration).isEqualTo(expectedGeneration)
    }

    @Test
    fun `Integration - two top-level CLI steps establish distinct step-attempt identities and admission sequences`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_integ_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        withExecutableScript(name = "fake-codex-int", content = "#!/bin/sh\nprintf 'codex-out'") { codexCli ->
            withExecutableScript(name = "fake-hermes-int", content = "#!/bin/sh\nprintf 'hermes-out'") { hermesCli ->
                try {
                    val wf =
                        workflow<String>(workflowName) {
                            codexStep(
                                name = codexStepName,
                                config = CodexStepConfig(cliPath = codexCli.toString(), security = StepSecurityConfig.Disabled),
                                prompt = { state, _ -> state },
                                merge = { state, response, _ -> "$state:$response" },
                            )
                            hermesStep(
                                name = hermesStepName,
                                config = HermesStepConfig(cliPath = hermesCli.toString(), security = StepSecurityConfig.Disabled),
                                prompt = { state, _ -> state },
                                merge = { state, response, _ -> "$state:$response" },
                            )
                        }.build { it }

                    val env = setupJdbcMultiStepEnvironment(pool, wf)
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    env.supervisor.attachScope(scope)

                    runBlocking {
                        val initialCheckpoint =
                            env.checkpointStore.save(
                                WorkflowCheckpoint(
                                    workflowName = workflowName,
                                    workflowId = workflowId,
                                    nextStepIndex = 0,
                                    stepExecutions = 0,
                                    lastCompletedStepName = null,
                                    statePayload = "start",
                                    metadata = wf.checkpointMetadata() + encodeGovernedRunAttribution(GOVERNED_IDENTITY),
                                ),
                            )
                        val expectedGeneration = checkNotNull(initialCheckpoint.checkpointGeneration)
                        val lease = env.leaseStore.claim(workflowName, workflowId, "worker-e2e-multi", initialCheckpoint.revision, 5_000)
                        env.supervisor.launch(initialCheckpoint, lease)

                        withTimeout(15_000) { while (env.supervisor.activeExecutionCount() != 0) delay(10) }

                        assertThat(env.supervisor.latestFailure(workflowId)).isNull()
                        val evidenceList = env.delegateStore.listEvidence(workflowName, workflowId).sortedBy { it.key.admissionSeq }
                        assertMultiStepEvidence(evidenceList, expectedGeneration)
                    }
                    scope.cancel()
                } finally {
                    pool.dispose()
                }
            }
        }
    }

    @Test
    fun `two CLI process invocations executing within one enclosing step attempt share parent attempt ID and produce distinct admission sequences`() {
        withExecutableScript(name = "fake-codex-shared", content = "#!/bin/sh\nprintf 'codex-out'") { codexCli ->
            withExecutableScript(name = "fake-hermes-shared", content = "#!/bin/sh\nprintf 'hermes-out'") { hermesCli ->
                val store = InMemoryWorkflowEffectEvidenceRecordStore()
                val sharedAttempt =
                    PersistedStepAttempt(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        stepName = "composite-step",
                        attemptId = "shared-attempt-123",
                    )
                var currentSeq = 0L
                val admission =
                    WorkflowProcessAdmission { sName, eKind ->
                        WorkflowProcessPermit(sName, eKind, ++currentSeq, checkpointGeneration = "gen-shared")
                    }

                val codexStep = createCodexStep(stepName = "composite-step", cliPath = codexCli.toString())
                val hermesStep = createHermesStep(stepName = "composite-step", cliPath = hermesCli.toString())

                runBlocking {
                    withContext(testScope(admission = admission, evidenceStore = store, attempt = sharedAttempt)) {
                        codexStep.execute(
                            workflowName = workflowName,
                            state = "initial",
                            context = WorkflowContext(workflowId),
                            observer = NoOpWorkflowObserver,
                        )
                        hermesStep.execute(
                            workflowName = workflowName,
                            state = "next",
                            context = WorkflowContext(workflowId),
                            observer = NoOpWorkflowObserver,
                        )
                    }
                }

                runBlocking {
                    val evidenceList = store.listEvidence(workflowName, workflowId).sortedBy { it.key.admissionSeq }
                    assertThat(evidenceList).hasSize(2)

                    val first = evidenceList[0]
                    val second = evidenceList[1]

                    assertThat(first.stepAttemptId).isEqualTo("shared-attempt-123")
                    assertThat(first.key.admissionSeq).isEqualTo(1L)
                    assertThat(first.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                    assertThat(first.key.checkpointGeneration).isEqualTo("gen-shared")

                    assertThat(second.stepAttemptId).isEqualTo("shared-attempt-123")
                    assertThat(second.key.admissionSeq).isEqualTo(2L)
                    assertThat(second.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                    assertThat(second.key.checkpointGeneration).isEqualTo("gen-shared")
                }
            }
        }
    }

    private suspend fun awaitPid(pidFile: Path): Long? {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadlineNanos) {
            if (Files.exists(pidFile)) {
                val text = Files.readString(pidFile).trim()
                if (text.isNotEmpty()) {
                    return text.toLongOrNull()
                }
            }
            delay(50)
        }
        return null
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
        cliType: String = "codex",
        cliPath: String = "fake-cli",
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

        val stepName = if (cliType == "codex") codexStepName else hermesStepName
        val wf =
            workflow<String>(workflowName) {
                if (cliType == "codex") {
                    codexStep(
                        name = stepName,
                        config = CodexStepConfig(cliPath = cliPath, security = StepSecurityConfig.Disabled),
                        prompt = { state, _ -> state },
                        merge = { state, response, _ -> "$state:${response.trim()}" },
                    )
                } else {
                    hermesStep(
                        name = stepName,
                        config = HermesStepConfig(cliPath = cliPath, security = StepSecurityConfig.Disabled),
                        prompt = { state, _ -> state },
                        merge = { state, response, _ -> "$state:${response.trim()}" },
                    )
                }
            }.build { it }

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
        return JdbcWorkerFixture(checkpointStore, leaseStore, attemptStore, wf, supervisor)
    }

    private class JdbcWorkerFixture(
        val checkpointStore: JdbcWorkflowCheckpointStore,
        val leaseStore: JdbcWorkflowLeaseStore,
        val attemptStore: JdbcStepAttemptRecordStore,
        val workflow: Workflow<String, String>,
        val supervisor: WorkflowExecutionSupervisor,
    )

    private object PlainStringCodec : WorkflowStateCodec<String> {
        override fun encode(state: String): String = state

        override fun decode(payload: String): String = payload
    }

    private fun withExecutableScript(
        name: String,
        content: String,
        block: (Path) -> Unit,
    ) {
        val script = Files.createTempFile(name, ".sh")
        try {
            Files.writeString(script, content + "\n")
            Files.setPosixFilePermissions(
                script,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            block(script)
        } finally {
            Files.deleteIfExists(script)
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
                runId = RunId("run-agent-cli-1"),
            )
    }
}
