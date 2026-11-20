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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic qualification test suite for D7-B1 durable Process effect evidence (Shell E7).
 *
 * Covers scenarios P1 through P11:
 * - P1: Normal execution with real governed worker + JDBC stores -> COMPLETED
 * - P2: Pre-admission refusal -> no evidence, no process
 * - P3: Missing lineage -> WorkflowEvidenceResolutionException before effect admission
 * - P4: Post-admission crash (W1) -> admission seq advances in checkpoint, child store empty, unclassified
 * - P5: Post-ADMITTED crash (W2) -> ADMITTED in JDBC, process never launched
 * - P6: Process acquisition failure -> ADMITTED transitions to UNCERTAIN, never DISPATCHED
 * - P7: Post-launch failure -> process tree terminated and reaped via terminateAndAwait
 * - P8: Timeout / cancellation -> UNCERTAIN, process terminated
 * - P9: Non-zero exit code distinction -> process evidence COMPLETED while step fails NON_ZERO_EXIT
 * - P10: Step retry -> distinct admission sequences and distinct attempt IDs
 * - P11: Ungoverned compatibility -> zero overhead and zero evidence writes
 */
class WorkflowProcessEffectEvidenceTest {
    private val workflowName = "test-process-workflow"
    private val workflowId = "run-process-1"
    private val stepName = "process-step"
    private val attemptId = "att-process-1"

    private fun testScope(
        admission: WorkflowProcessAdmission? = null,
        evidenceStore: WorkflowEffectEvidenceRecordStore? = null,
        attempt: PersistedStepAttempt? = PersistedStepAttempt(workflowName, workflowId, stepName, attemptId),
    ) = WorkflowProcessAdmissionScope(admission) +
        WorkflowEffectEvidenceStoreScope(evidenceStore) +
        (if (attempt != null) StepAttemptScope(attempt) else kotlin.coroutines.EmptyCoroutineContext)

    private fun createShellStep(
        stepName: String = this.stepName,
        executable: String = "echo",
        commandArgs: List<String> = listOf("echo", "hello"),
        config: ShellStepConfig = ShellStepConfig(allowedCommands = setOf("echo", "sh", "sleep", "/bin/sh")),
        processStarter: (ProcessBuilder) -> Process = { it.start() },
    ): ShellWorkflowStep<String> =
        ShellWorkflowStep(
            name = stepName,
            definition = ShellCommandDefinition(executable = executable),
            commandBuilder = { state, _ -> ShellCommand(command = commandArgs) },
            merge = { state, result, _ -> "$state:${result.stdout.trim()}" },
            config = config,
            processStarter = processStarter,
        )

    @Test
    fun `P1 - normal execution with real governed worker and JDBC stores records COMPLETED evidence`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_p1_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val fixture = createJdbcWorkerFixture(pool = pool, schemaStore = delegateStore)
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
                assertThat(record.key.admissionSeq).isEqualTo(2L) // 1 was step admission E11, 2 is process admission E7
                assertThat(record.stepName).isEqualTo(stepName)
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

    @Test
    fun `P2 - pre-admission refusal throws without creating evidence or launching process`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step = createShellStep()
        val refusingAdmission =
            WorkflowProcessAdmission { _, _ ->
                throw WorkflowAdmissionRefusedException("workflow execution has been cancelled")
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = refusingAdmission, evidenceStore = store)) {
                    step.execute(
                        workflowName = workflowName,
                        state = "initial",
                        context = WorkflowContext(workflowId),
                        observer = NoOpWorkflowObserver,
                    )
                }
            }
        }.isInstanceOf(WorkflowAdmissionRefusedException::class.java)

        runBlocking {
            assertThat(store.listEvidence(workflowName, workflowId)).isEmpty()
        }
    }

    @Test
    fun `P3 - missing lineage throws WorkflowEvidenceResolutionException before effect admission`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step = createShellStep()
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
    fun `P4 - W1 crash window - admission commits in checkpoint store when child evidence write fails`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_p4_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
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

    @Test
    fun `P5 - W2 crash window - ADMITTED evidence persists in JDBC store when crash occurs before process launch`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_p5_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
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
                assertThat(persistedEvidence.key.admissionSeq).isEqualTo(2L)
                assertThat(persistedEvidence.state).isEqualTo(WorkflowEffectEvidenceState.ADMITTED)
                assertThat(persistedEvidence.completedAtEpochMillis).isNull()
            }
            scope.cancel()
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `P6 - process acquisition failure transitions ADMITTED directly to UNCERTAIN without DISPATCHED`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step =
            createShellStep(
                executable = "/definitely/nonexistent/tramai_binary_404",
                commandArgs = listOf("/definitely/nonexistent/tramai_binary_404"),
                config = ShellStepConfig(allowedCommands = setOf("/definitely/nonexistent/tramai_binary_404")),
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
        }.isInstanceOf(WorkflowShellException::class.java)
            .matches { (it as WorkflowShellException).failureCode == WorkflowStepFailureCode.START_FAILED }

        runBlocking {
            val evidence = store.listEvidence(workflowName, workflowId).single()
            assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(evidence.diagnosticSummary).isNotNull()
            assertThat(evidence.completedAtEpochMillis).isNull()
        }
    }

    @Test
    fun `P7 - post-launch evidence failure terminates process tree via terminateAndAwait`(
        @TempDir tempDir: Path,
    ) {
        val pidFile = tempDir.resolve("child.pid")
        val delegateStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val failingStore =
            object : WorkflowEffectEvidenceRecordStore by delegateStore {
                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                    if (record.state == WorkflowEffectEvidenceState.DISPATCHED) {
                        error("simulated store failure on DISPATCHED write")
                    }
                    return delegateStore.updateEvidence(record)
                }
            }

        val script = "echo $$ > '${pidFile.toAbsolutePath()}'; sleep 30"
        val step =
            createShellStep(
                executable = "sh",
                commandArgs = listOf("sh", "-c", script),
                config = ShellStepConfig(allowedCommands = setOf("sh")),
            )
        val admission =
            WorkflowProcessAdmission { sName, eKind ->
                WorkflowProcessPermit(sName, eKind, 1L)
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = failingStore)) {
                    step.execute(
                        workflowName = workflowName,
                        state = "init",
                        context = WorkflowContext(workflowId),
                        observer = NoOpWorkflowObserver,
                    )
                }
            }
        }.isInstanceOf(WorkflowShellException::class.java)

        // Await pidFile creation
        runBlocking {
            val pid = awaitPid(pidFile)
            assertThat(pid).isNotNull()

            // Verify process handle is no longer alive (cleanly reaped, no orphan)
            val handle = ProcessHandle.of(pid!!).orElse(null)
            if (handle != null) {
                withTimeout(5_000) {
                    while (handle.isAlive) delay(50)
                }
                assertThat(handle.isAlive).isFalse()
            }
        }
    }

    @Test
    fun `P8 - timeout marks evidence UNCERTAIN and terminates process`(
        @TempDir tempDir: Path,
    ) {
        val pidFile = tempDir.resolve("timeout_child.pid")
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val script = "echo $$ > '${pidFile.toAbsolutePath()}'; sleep 30"
        val step =
            createShellStep(
                executable = "sh",
                commandArgs = listOf("sh", "-c", script),
                config = ShellStepConfig(timeoutSeconds = 1, allowedCommands = setOf("sh")),
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
        }.isInstanceOf(WorkflowShellException::class.java)
            .matches { (it as WorkflowShellException).failureCode == WorkflowStepFailureCode.TIMEOUT }

        runBlocking {
            val evidence = store.listEvidence(workflowName, workflowId).single()
            assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(evidence.diagnosticSummary).contains("timed out")

            val pid = awaitPid(pidFile)
            if (pid != null) {
                val handle = ProcessHandle.of(pid).orElse(null)
                if (handle != null) {
                    withTimeout(5_000) {
                        while (handle.isAlive) delay(50)
                    }
                    assertThat(handle.isAlive).isFalse()
                }
            }
        }
    }

    @Test
    fun `P9 - non-zero exit code records COMPLETED evidence while step fails NON_ZERO_EXIT`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step =
            createShellStep(
                executable = "sh",
                commandArgs = listOf("sh", "-c", "echo failure-out; exit 42"),
                config = ShellStepConfig(failOnNonZeroExit = true, allowedCommands = setOf("sh")),
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
        }.isInstanceOf(WorkflowShellException::class.java)
            .matches { (it as WorkflowShellException).failureCode == WorkflowStepFailureCode.NON_ZERO_EXIT }

        runBlocking {
            val evidence = store.listEvidence(workflowName, workflowId).single()
            assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(evidence.completedAtEpochMillis).isNotNull()
            assertThat(evidence.diagnosticSummary).isNull()
        }
    }

    @Test
    fun `P10 - retried step execution generates distinct admission sequences and attempt IDs`() {
        runBlocking {
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            var admissionCounter = 0L

            val admission =
                WorkflowProcessAdmission { sName, eKind ->
                    WorkflowProcessPermit(sName, eKind, ++admissionCounter)
                }

            // Attempt 1: fails
            val attempt1 = PersistedStepAttempt(workflowName, workflowId, stepName, "attempt-1")
            val failingStep =
                createShellStep(
                    executable = "sh",
                    commandArgs = listOf("sh", "-c", "exit 1"),
                    config = ShellStepConfig(failOnNonZeroExit = true, allowedCommands = setOf("sh")),
                )

            assertThatThrownBy {
                runBlocking {
                    withContext(testScope(admission = admission, evidenceStore = store, attempt = attempt1)) {
                        failingStep.execute(
                            workflowName = workflowName,
                            state = "init",
                            context = WorkflowContext(workflowId),
                            observer = NoOpWorkflowObserver,
                        )
                    }
                }
            }.isInstanceOf(WorkflowShellException::class.java)

            // Attempt 2: succeeds
            val attempt2 = PersistedStepAttempt(workflowName, workflowId, stepName, "attempt-2")
            val successfulStep =
                createShellStep(
                    executable = "echo",
                    commandArgs = listOf("echo", "retry-success"),
                )

            val result =
                withContext(testScope(admission = admission, evidenceStore = store, attempt = attempt2)) {
                    successfulStep.execute(
                        workflowName = workflowName,
                        state = "init",
                        context = WorkflowContext(workflowId),
                        observer = NoOpWorkflowObserver,
                    )
                }

            assertThat(result).isEqualTo("init:retry-success")

            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)

            val record1 = records.first { it.stepAttemptId == "attempt-1" }
            val record2 = records.first { it.stepAttemptId == "attempt-2" }

            assertThat(record1.key.admissionSeq).isEqualTo(1L)
            assertThat(record1.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED) // Process effect completed (exit 1)

            assertThat(record2.key.admissionSeq).isEqualTo(2L)
            assertThat(record2.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        }
    }

    @Test
    fun `P11 - ungoverned workflow executes Shell normally and writes no evidence even with evidence store configured`() {
        runBlocking {
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
            checkpointStore.evidenceStore = evidenceStore

            val wf =
                workflow<String>(workflowName) {
                    shellStep(
                        name = stepName,
                        config = ShellStepConfig(allowedCommands = setOf("echo")),
                        definition = ShellCommandDefinition(executable = "echo"),
                        command = { state, _ -> ShellCommand(command = listOf("echo", state)) },
                        merge = { state, result, _ -> "$state:${result.stdout.trim()}" },
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
            assertThat(runOutcome).isEqualTo("ungoverned:ungoverned")
            assertThat(evidenceStore.listEvidence(workflowName, "ungoverned-run")).isEmpty()

            checkpointStore.save(
                WorkflowCheckpoint(
                    workflowName = workflowName,
                    workflowId = "ungoverned-resume",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = "resumed",
                    metadata = wf.checkpointMetadata(),
                ),
            )
            val resumeOutcome = wf.resume(WorkflowContext("ungoverned-resume"), persistence = persistence)
            assertThat(resumeOutcome).isEqualTo("resumed:resumed")
            assertThat(evidenceStore.listEvidence(workflowName, "ungoverned-resume")).isEmpty()
        }
    }

    @Test
    fun `P12 - post-launch evidence store stall triggers bounded cleanup without external cancellation`(
        @TempDir tempDir: Path,
    ) {
        val pidFile = tempDir.resolve("stalled_child.pid")
        val delegateStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val updateDispatchedEntered = CompletableDeferred<Unit>()
        val neverReleasedStall = CompletableDeferred<Unit>()
        var processAliveDuringUncertainUpdate: Boolean? = null
        var childPid: Long? = null

        val stallingStore =
            object : WorkflowEffectEvidenceRecordStore by delegateStore {
                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                    if (record.state == WorkflowEffectEvidenceState.DISPATCHED) {
                        updateDispatchedEntered.complete(Unit)
                        // Never released: simulates an evidence store that hangs indefinitely on DISPATCHED write
                        neverReleasedStall.await()
                    }
                    if (record.state == WorkflowEffectEvidenceState.UNCERTAIN) {
                        childPid?.let { pid ->
                            processAliveDuringUncertainUpdate = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                        }
                    }
                    return delegateStore.updateEvidence(record)
                }
            }

        val script = "echo $$ > '${pidFile.toAbsolutePath()}'; sleep 30"
        val step =
            createShellStep(
                executable = "sh",
                commandArgs = listOf("sh", "-c", script),
                config = ShellStepConfig(allowedCommands = setOf("sh"), timeoutSeconds = 1),
            )
        val admission =
            WorkflowProcessAdmission { sName, eKind ->
                WorkflowProcessPermit(sName, eKind, 1L)
            }

        runBlocking {
            var caughtException: Throwable? = null
            val job =
                launch(Dispatchers.Default) {
                    try {
                        withContext(testScope(admission = admission, evidenceStore = stallingStore)) {
                            step.execute(
                                workflowName = workflowName,
                                state = "init",
                                context = WorkflowContext(workflowId),
                                observer = NoOpWorkflowObserver,
                            )
                        }
                    } catch (e: Throwable) {
                        caughtException = e
                    }
                }

            childPid = awaitPid(pidFile)
            assertThat(childPid).isNotNull()
            updateDispatchedEntered.await()

            val handle = ProcessHandle.of(childPid!!).orElse(null)
            assertThat(handle).isNotNull()
            assertThat(handle?.isAlive).isTrue()

            // Do NOT cancel the workflow and do NOT release the stall!
            // The configured execution deadline (1s) must fire and cause process cleanup!
            job.join()

            assertThat(caughtException).isInstanceOf(WorkflowShellException::class.java)

            // Bounded graceful-to-forced process cleanup occurred
            withTimeout(5_000) {
                while (handle?.isAlive == true) delay(50)
            }
            assertThat(handle?.isAlive).isFalse()

            // Proves process cleanup completed before UNCERTAIN update was attempted
            assertThat(processAliveDuringUncertainUpdate).isFalse()
        }
    }

    @Test
    fun `P14 - secondary cancellation during uncertainty persistence does not replace primary failure`() {
        val delegateStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val secondaryCancellation = CancellationException("secondary store cancellation")
        val store = createCancellingUncertaintyStore(delegateStore, secondaryCancellation)
        val key = WorkflowEffectEvidenceKey(workflowName, "run-disc-1", "", 1L)

        runBlocking {
            assertDirectUncertaintySuppression(delegateStore, store, key, secondaryCancellation)
            assertShellExecutionPreservesPrimaryFailure(store)
        }
    }

    @Test
    fun `P15 - step timeout terminates descendant and unblocks output capture when process tree stalls`() {
        val parentPidFile = Files.createTempFile("d7-p15-parent", ".pid")
        val childPidFile = Files.createTempFile("d7-p15-child", ".pid")
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        var parentHandle: ProcessHandle? = null
        var childHandle: ProcessHandle? = null

        try {
            val script =
                "echo $$ > '${parentPidFile.toAbsolutePath()}'; " +
                    "sleep 30 & child=$!; echo \$child > '${childPidFile.toAbsolutePath()}'; " +
                    "wait"
            val step =
                createShellStep(
                    executable = "sh",
                    commandArgs = listOf("sh", "-c", script),
                    config = ShellStepConfig(allowedCommands = setOf("sh"), timeoutSeconds = 1),
                )
            val admission = WorkflowProcessAdmission { s, e -> WorkflowProcessPermit(s, e, 1L) }

            runBlocking {
                withTimeout(10_000) {
                    assertDescendantCleanupOnOutputStall(
                        ProcessStallFixture(
                            step = step,
                            admission = admission,
                            evidenceStore = evidenceStore,
                            parentPidFile = parentPidFile,
                            childPidFile = childPidFile,
                            onHandlesResolved = { pHandle, cHandle ->
                                parentHandle = pHandle
                                childHandle = cHandle
                            },
                        ),
                    )
                }
            }
        } finally {
            Files.deleteIfExists(parentPidFile)
            Files.deleteIfExists(childPidFile)
            runCatching { parentHandle?.destroyForcibly() }
            runCatching { childHandle?.destroyForcibly() }
        }
    }

    @Test
    fun `P16 - timeout scope cancellation unblocks output capture when root process has exited but stream reader is blocked`() {
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
            createShellStep(
                executable = "echo",
                commandArgs = listOf("echo", "test"),
                config = ShellStepConfig(allowedCommands = setOf("echo"), timeoutSeconds = 1),
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
                assertThat(caughtFailure).isInstanceOf(WorkflowShellException::class.java)
                val failureCode = (caughtFailure as WorkflowShellException).failureCode
                assertThat(failureCode).isEqualTo(WorkflowStepFailureCode.TIMEOUT)

                val records = evidenceStore.listEvidence(workflowName, workflowId)
                assertThat(records).hasSize(1)
                assertThat(records.first().state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            }
        }
    }

    private fun createCancellingUncertaintyStore(
        delegate: WorkflowEffectEvidenceRecordStore,
        cancellation: CancellationException,
    ): WorkflowEffectEvidenceRecordStore =
        object : WorkflowEffectEvidenceRecordStore by delegate {
            override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                if (record.state == WorkflowEffectEvidenceState.UNCERTAIN) {
                    throw cancellation
                }
                return delegate.updateEvidence(record)
            }
        }

    private suspend fun assertDirectUncertaintySuppression(
        delegateStore: InMemoryWorkflowEffectEvidenceRecordStore,
        store: WorkflowEffectEvidenceRecordStore,
        key: WorkflowEffectEvidenceKey,
        secondaryCancellation: CancellationException,
    ) {
        delegateStore.recordEvidence(
            WorkflowEffectEvidenceRecord(
                key = key,
                stepName = stepName,
                stepAttemptId = attemptId,
                effectKind = PROCESS_EFFECT_KIND,
                state = WorkflowEffectEvidenceState.ADMITTED,
                createdAtEpochMillis = 1000L,
            ),
        )
        val primaryError = IllegalStateException("known primary failure")
        recordUncertainFailure(key, store, primaryError)
        assertThat(primaryError.suppressed).anyMatch {
            it is CancellationException && it.message == "secondary store cancellation"
        }

        val primaryCancellation = CancellationException("primary cancellation")
        recordUncertainFailure(key, store, primaryCancellation)
        assertThat(primaryCancellation.suppressed).anyMatch {
            it is SanitizedEvidenceDiagnosticException
        }
        assertThat(primaryCancellation.suppressed).noneMatch {
            it.message == secondaryCancellation.message
        }
    }

    private suspend fun assertShellExecutionPreservesPrimaryFailure(store: WorkflowEffectEvidenceRecordStore) {
        val step =
            createShellStep(
                executable = "sh",
                commandArgs = listOf("sh", "-c", "sleep 30"),
                config = ShellStepConfig(allowedCommands = setOf("sh"), timeoutSeconds = 1),
            )
        val admission = WorkflowProcessAdmission { s, e -> WorkflowProcessPermit(s, e, 1L) }
        var caughtFailure: Throwable? = null
        try {
            withContext(testScope(admission = admission, evidenceStore = store)) {
                step.execute(workflowName, "init", WorkflowContext(workflowId), NoOpWorkflowObserver)
            }
        } catch (error: Throwable) {
            caughtFailure = error
        }

        assertThat(caughtFailure).isInstanceOf(WorkflowShellException::class.java)
        assertThat((caughtFailure as WorkflowShellException).failureCode).isEqualTo(WorkflowStepFailureCode.TIMEOUT)
        assertThat(currentCoroutineContext().job.isActive).isTrue()
    }

    private class ProcessStallFixture(
        val step: ShellWorkflowStep<String>,
        val admission: WorkflowProcessAdmission,
        val evidenceStore: InMemoryWorkflowEffectEvidenceRecordStore,
        val parentPidFile: Path,
        val childPidFile: Path,
        val onHandlesResolved: (ProcessHandle?, ProcessHandle?) -> Unit,
    )

    private suspend fun assertDescendantCleanupOnOutputStall(fixture: ProcessStallFixture) {
        var caughtException: Throwable? = null
        val job =
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    withContext(testScope(admission = fixture.admission, evidenceStore = fixture.evidenceStore)) {
                        fixture.step.execute(workflowName, "init", WorkflowContext(workflowId), NoOpWorkflowObserver)
                    }
                } catch (e: Throwable) {
                    caughtException = e
                }
            }

        try {
            val parentPid = awaitPid(fixture.parentPidFile)
            val childPid = awaitPid(fixture.childPidFile)
            assertThat(parentPid).isNotNull()
            assertThat(childPid).isNotNull()

            val parentHandle = ProcessHandle.of(parentPid!!).orElse(null)
            val childHandle = ProcessHandle.of(childPid!!).orElse(null)
            fixture.onHandlesResolved(parentHandle, childHandle)
            assertThat(parentHandle?.isAlive).isTrue()
            assertThat(childHandle?.isAlive).isTrue()

            withTimeout(5_000) {
                job.join()
            }

            assertThat(caughtException).isInstanceOf(WorkflowShellException::class.java)
            assertThat((caughtException as WorkflowShellException).failureCode).isEqualTo(WorkflowStepFailureCode.TIMEOUT)

            withTimeout(5_000) {
                while (parentHandle?.isAlive == true) delay(25)
            }
            assertThat(parentHandle?.isAlive).isFalse()

            withTimeout(5_000) {
                while (childHandle?.isAlive == true) delay(25)
            }
            assertThat(childHandle?.isAlive).isFalse()

            val records = fixture.evidenceStore.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(1)
            assertThat(records.first().state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
        } finally {
            if (job.isActive) {
                job.cancel()
                runCatching { withTimeout(2_000) { job.join() } }
            }
        }
    }

    @Test
    fun `P13 - W3 crash window - DISPATCHED evidence persists in JDBC store when completion evidence write fails after process exit`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_w3_shell_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
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

        val wf =
            workflow<String>(workflowName) {
                shellStep(
                    name = stepName,
                    config = ShellStepConfig(allowedCommands = setOf("echo")),
                    definition = ShellCommandDefinition(executable = "echo"),
                    command = { state, _ -> ShellCommand(command = listOf("echo", state)) },
                    merge = { state, result, _ -> "$state:${result.stdout.trim()}" },
                )
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

    private companion object {
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
                runId = RunId("run-process-1"),
            )
    }
}
