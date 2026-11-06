@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.util.Optional
import java.util.UUID
import javax.net.ssl.SSLSession
import kotlin.coroutines.CoroutineContext
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse

/**
 * End-to-end crash-window (W1, W2, W3) and ungoverned compatibility tests for D7-A per-effect evidence.
 */
class WorkflowEffectEvidenceCrashWindowTest {
    private val workflowName = "test-workflow"
    private val workflowId = "run-1"
    private val stepName = "fetch-step"

    @Test
    fun `ungoverned workflow dispatches HTTP normally and writes no evidence when persistence configures an evidence store`() {
        runBlocking {
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
            checkpointStore.evidenceStore = evidenceStore

            var sendCount = 0
            val transport =
                FakeHttpTransport { _ ->
                    sendCount++
                    FakeResponse(200, "UNGOVERNED-OK")
                }

            val wf =
                workflow<String>(workflowName) {
                    outboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth(allowPrivateDestinations = true)
                    httpTransport = transport
                    httpStep(
                        name = stepName,
                        config = HttpStepConfig(allowedHosts = setOf("127.0.0.1")),
                        request = { _, _ -> HttpRequest("GET", "http://127.0.0.1/ungoverned") },
                        merge = { state, response, _ -> "$state:${response.body}" },
                    )
                }.build { it }

            val persistence =
                WorkflowPersistence(
                    checkpointStore = checkpointStore,
                    stateCodec = PlainStringCodec,
                    deleteCheckpointOnCompletion = false,
                    evidenceStore = evidenceStore,
                )

            val runOutcome = wf.run("init", WorkflowContext("ungoverned-run"), persistence = persistence)
            assertThat(runOutcome).isEqualTo("init:UNGOVERNED-OK")
            assertThat(sendCount).isEqualTo(1)
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
            assertThat(resumeOutcome).isEqualTo("resumed:UNGOVERNED-OK")
            assertThat(sendCount).isEqualTo(2)
            assertThat(evidenceStore.listEvidence(workflowName, "ungoverned-resume")).isEmpty()
        }
    }

    @Test
    fun `W1 crash window - admission commits in checkpoint store when child evidence write fails before transport`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_w1_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val failingRecordStore =
                object : WorkflowEffectEvidenceRecordStore by delegateStore {
                    override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                        error("W1 crash after admission commit before child evidence write")
                }
            var sendCount = 0
            val fixture =
                createJdbcWorkerFixture(
                    pool = pool,
                    evidenceStoreOverride = failingRecordStore,
                    schemaStore = delegateStore,
                    transport =
                        FakeHttpTransport { _ ->
                            sendCount++
                            FakeResponse(200, "UNEXPECTED")
                        },
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            fixture.supervisor.attachScope(scope)

            runBlocking {
                val initialCheckpoint = saveGovernedInitialCheckpoint(fixture)
                val expectedGeneration = checkNotNull(initialCheckpoint.checkpointGeneration)
                val lease =
                    fixture.leaseStore.claim(workflowName, workflowId, "worker-e2e-1", initialCheckpoint.revision, 5_000)
                fixture.supervisor.launch(initialCheckpoint, lease)

                withTimeout(5_000) {
                    while (fixture.supervisor.activeExecutionCount() != 0) delay(10)
                }

                assertThat(sendCount).isEqualTo(0)
                assertThat(fixture.supervisor.latestFailure(workflowId)).isNotNull()
                assertThat(delegateStore.listEvidence(workflowName, workflowId)).isEmpty()
                assertThat(delegateStore.classifyAdmission(workflowName, workflowId, expectedGeneration, 2L))
                    .isEqualTo(WorkflowEffectClassification.Unclassified)

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
    fun `W2 crash window - ADMITTED evidence persists in JDBC store when crash occurs before transport handoff`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_w2_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val crashAfterAdmitted =
                object : WorkflowEffectEvidenceRecordStore by delegateStore {
                    override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                        delegateStore.recordEvidence(record)
                        error("W2 process terminated immediately after durable ADMITTED write")
                    }
                }
            var sendCount = 0
            val countingTransport =
                FakeHttpTransport { _ ->
                    sendCount++
                    FakeResponse(200, "UNEXPECTED")
                }
            val fixture =
                createJdbcWorkerFixture(
                    pool = pool,
                    evidenceStoreOverride = crashAfterAdmitted,
                    schemaStore = delegateStore,
                    transport = countingTransport,
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            fixture.supervisor.attachScope(scope)

            runBlocking {
                val initialCheckpoint = saveGovernedInitialCheckpoint(fixture)
                val expectedGeneration = checkNotNull(initialCheckpoint.checkpointGeneration)
                val lease =
                    fixture.leaseStore.claim(workflowName, workflowId, "worker-e2e-1", initialCheckpoint.revision, 5_000)
                fixture.supervisor.launch(initialCheckpoint, lease)

                withTimeout(5_000) {
                    while (fixture.supervisor.activeExecutionCount() != 0) delay(10)
                }

                assertThat(sendCount).isEqualTo(0)
                assertThat(fixture.supervisor.latestFailure(workflowId)).isNotNull()
                val persistedEvidence = delegateStore.listEvidence(workflowName, workflowId).single()
                assertThat(persistedEvidence.key.checkpointGeneration).isEqualTo(expectedGeneration)
                assertThat(persistedEvidence.key.admissionSeq).isEqualTo(2L)
                assertThat(persistedEvidence.stepAttemptId).isEqualTo("attempt-e2e-1")
                assertThat(persistedEvidence.state).isEqualTo(WorkflowEffectEvidenceState.ADMITTED)
                assertThat(persistedEvidence.completedAtEpochMillis).isNull()
            }
            scope.cancel()
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `ADMITTED evidence persists in JDBC store when transport fails and crash prevents UNCERTAIN transition`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_tx_fail_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val crashOnUpdate =
                object : WorkflowEffectEvidenceRecordStore by delegateStore {
                    override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                        error("Process terminated before UNCERTAIN update could persist")
                }
            var sendCount = 0
            val crashingTransport =
                object : HttpTransport {
                    override val capability: HttpTransportCapability = HttpTransportCapability.PRE_CONNECT_VALIDATION

                    override suspend fun send(
                        httpRequest: JdkHttpRequest,
                        blockingDispatcher: CoroutineContext,
                        onConnected: (InetAddress) -> Unit,
                    ): ControlledSendResult {
                        sendCount++
                        error("Transport connection crashed before handoff")
                    }
                }
            val fixture =
                createJdbcWorkerFixture(
                    pool = pool,
                    evidenceStoreOverride = crashOnUpdate,
                    schemaStore = delegateStore,
                    transport = crashingTransport,
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            fixture.supervisor.attachScope(scope)

            runBlocking {
                val initialCheckpoint = saveGovernedInitialCheckpoint(fixture)
                val expectedGeneration = checkNotNull(initialCheckpoint.checkpointGeneration)
                val lease =
                    fixture.leaseStore.claim(workflowName, workflowId, "worker-e2e-1", initialCheckpoint.revision, 5_000)
                fixture.supervisor.launch(initialCheckpoint, lease)

                withTimeout(5_000) {
                    while (fixture.supervisor.activeExecutionCount() != 0) delay(10)
                }

                assertThat(sendCount).isEqualTo(1)
                assertThat(fixture.supervisor.latestFailure(workflowId)).isNotNull()
                val persistedEvidence = delegateStore.listEvidence(workflowName, workflowId).single()
                assertThat(persistedEvidence.key.checkpointGeneration).isEqualTo(expectedGeneration)
                assertThat(persistedEvidence.key.admissionSeq).isEqualTo(2L)
                assertThat(persistedEvidence.stepAttemptId).isEqualTo("attempt-e2e-1")
                assertThat(persistedEvidence.state).isEqualTo(WorkflowEffectEvidenceState.ADMITTED)
                assertThat(persistedEvidence.completedAtEpochMillis).isNull()
            }
            scope.cancel()
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `W3 crash window - DISPATCHED evidence persists in JDBC store when completion evidence write fails after transport`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_w3_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val failOnCompletedStore =
                object : WorkflowEffectEvidenceRecordStore by delegateStore {
                    override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                        if (record.state == WorkflowEffectEvidenceState.COMPLETED) {
                            error("W3 crash after transport return before COMPLETED write")
                        }
                        return delegateStore.updateEvidence(record)
                    }
                }
            var sendCount = 0
            val fixture =
                createJdbcWorkerFixture(
                    pool = pool,
                    evidenceStoreOverride = failOnCompletedStore,
                    schemaStore = delegateStore,
                    transport =
                        FakeHttpTransport { _ ->
                            sendCount++
                            FakeResponse(200, "W3-BODY")
                        },
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            fixture.supervisor.attachScope(scope)

            runBlocking {
                val initialCheckpoint = saveGovernedInitialCheckpoint(fixture)
                val expectedGeneration = checkNotNull(initialCheckpoint.checkpointGeneration)
                val lease =
                    fixture.leaseStore.claim(workflowName, workflowId, "worker-e2e-1", initialCheckpoint.revision, 5_000)
                fixture.supervisor.launch(initialCheckpoint, lease)

                withTimeout(5_000) {
                    while (fixture.supervisor.activeExecutionCount() != 0) delay(10)
                }

                assertThat(sendCount).isEqualTo(1)
                assertThat(fixture.supervisor.latestFailure(workflowId)).isNotNull()
                val persistedEvidence = delegateStore.listEvidence(workflowName, workflowId).single()
                assertThat(persistedEvidence.key.checkpointGeneration).isEqualTo(expectedGeneration)
                assertThat(persistedEvidence.key.admissionSeq).isEqualTo(2L)
                assertThat(persistedEvidence.stepAttemptId).isEqualTo("attempt-e2e-1")
                assertThat(persistedEvidence.state).isEqualTo(WorkflowEffectEvidenceState.DISPATCHED)
                assertThat(persistedEvidence.completedAtEpochMillis).isNull()
            }
            scope.cancel()
        } finally {
            pool.dispose()
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
        evidenceStoreOverride: WorkflowEffectEvidenceRecordStore,
        schemaStore: JdbcWorkflowEffectEvidenceRecordStore,
        transport: HttpTransport,
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
                outboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth(allowPrivateDestinations = true)
                httpTransport = transport
                httpStep(
                    name = stepName,
                    config = HttpStepConfig(allowedHosts = setOf("127.0.0.1")),
                    request = { _, _ -> HttpRequest("GET", "http://127.0.0.1/e2e") },
                    merge = { state, response, _ -> "$state:${response.body}" },
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

    private class FakeHttpTransport(
        private val handler: (JdkHttpRequest) -> JdkHttpResponse<InputStream>,
    ) : HttpTransport {
        override val capability: HttpTransportCapability = HttpTransportCapability.PRE_CONNECT_VALIDATION

        override suspend fun send(
            httpRequest: JdkHttpRequest,
            blockingDispatcher: CoroutineContext,
            onConnected: (InetAddress) -> Unit,
        ): ControlledSendResult {
            onConnected(InetAddress.getByName("127.0.0.1"))
            return ControlledSendResult(handler(httpRequest))
        }
    }

    private class FakeResponse(
        private val statusCode: Int,
        private val bodyText: String,
    ) : JdkHttpResponse<InputStream> {
        override fun statusCode(): Int = statusCode

        override fun body(): InputStream = ByteArrayInputStream(bodyText.toByteArray())

        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun uri(): URI = URI.create("http://127.0.0.1/")

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

        override fun request(): JdkHttpRequest? = null

        override fun previousResponse(): Optional<JdkHttpResponse<InputStream>> = Optional.empty()

        override fun sslSession(): Optional<SSLSession> = Optional.empty()
    }

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
                runId = RunId("run-1"),
            )
    }
}
