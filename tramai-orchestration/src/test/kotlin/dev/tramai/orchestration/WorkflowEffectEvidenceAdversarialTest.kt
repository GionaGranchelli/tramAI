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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.time.Clock
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSession
import kotlin.coroutines.CoroutineContext
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse

/**
 * Adversarial test suite for D7-A per-effect evidence and dispatch boundaries.
 */
class WorkflowEffectEvidenceAdversarialTest {
    private val workflowName = "test-workflow"
    private val workflowId = "run-1"
    private val stepName = "fetch-step"
    private val attemptId = "att-42"

    private fun testScope(
        admission: WorkflowHttpAdmission? = null,
        evidenceStore: WorkflowEffectEvidenceRecordStore? = null,
        attempt: PersistedStepAttempt? = PersistedStepAttempt(workflowName, workflowId, stepName, attemptId),
    ) = WorkflowHttpAdmissionScope(admission) +
        WorkflowEffectEvidenceStoreScope(evidenceStore) +
        (if (attempt != null) StepAttemptScope(attempt) else kotlin.coroutines.EmptyCoroutineContext)

    private fun testHttpStep(
        stepName: String = this.stepName,
        url: String = "http://127.0.0.1/data",
    ): HttpWorkflowStep<String> =
        HttpWorkflowStep(
            name = stepName,
            requestBuilder = { _, _ -> HttpRequest("GET", url) },
            merge = { s, _, _ -> s },
            config = HttpStepConfig(allowedHosts = setOf("127.0.0.1")),
        )

    @Test
    fun `normal return records ADMITTED then DISPATCHED then COMPLETED evidence`() {
        runBlocking {
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            var sequenceCounter = 0L
            val admission =
                WorkflowHttpAdmission { sName, eKind ->
                    WorkflowHttpPermit(
                        stepName = sName,
                        effectKind = eKind,
                        admissionSequence = ++sequenceCounter,
                        checkpointGeneration = "gen-1",
                    )
                }

            val transport =
                FakeHttpTransport { _ ->
                    FakeResponse(200, "OK")
                }

            val step = testHttpStep()
            val request = makeExecutionRequest("init-state", transport)

            withContext(testScope(admission, store)) {
                step.execute(request)
            }

            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(1)
            val record = records.single()
            assertThat(record.key.admissionSeq).isEqualTo(1L)
            assertThat(record.stepAttemptId).isEqualTo(attemptId)
            assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(record.completedAtEpochMillis).isNotNull()
        }
    }

    @Test
    fun `two HTTP sends in the same step produce distinct monotonic admission sequences under same attempt`() {
        runBlocking {
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            var sequenceCounter = 0L
            val admission =
                WorkflowHttpAdmission { sName, eKind ->
                    WorkflowHttpPermit(
                        stepName = sName,
                        effectKind = eKind,
                        admissionSequence = ++sequenceCounter,
                        checkpointGeneration = "gen-1",
                    )
                }

            val transport = FakeHttpTransport { _ -> FakeResponse(200, "OK") }
            val step = testHttpStep()
            val request = makeExecutionRequest("init-state", transport)

            withContext(testScope(admission, store)) {
                step.execute(request)
                step.execute(request)
            }

            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)
            assertThat(records[0].key.admissionSeq).isEqualTo(1L)
            assertThat(records[0].stepAttemptId).isEqualTo(attemptId)
            assertThat(records[0].state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)

            assertThat(records[1].key.admissionSeq).isEqualTo(2L)
            assertThat(records[1].stepAttemptId).isEqualTo(attemptId)
            assertThat(records[1].state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        }
    }

    @Test
    fun `transport failure transitions evidence to UNCERTAIN and preserves diagnostic summary`() {
        runBlocking {
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            var sequenceCounter = 0L
            val admission =
                WorkflowHttpAdmission { sName, eKind ->
                    WorkflowHttpPermit(
                        stepName = sName,
                        effectKind = eKind,
                        admissionSequence = ++sequenceCounter,
                        checkpointGeneration = "gen-1",
                    )
                }

            val transport =
                FakeHttpTransport { _ ->
                    throw java.io.IOException("Connection reset by peer")
                }

            val step = testHttpStep(url = "http://127.0.0.1/fail")
            val request = makeExecutionRequest("init-state", transport)

            assertThatThrownBy {
                runBlocking {
                    withContext(testScope(admission, store)) {
                        step.execute(request)
                    }
                }
            }.isInstanceOf(WorkflowHttpException::class.java)

            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(1)
            val record = records.single()
            assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(record.diagnosticSummary).contains("Connection reset by peer")
        }
    }

    @Test
    fun `refused admission never invokes transport and writes no evidence record`() {
        runBlocking {
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            val admission =
                WorkflowHttpAdmission { sName, eKind ->
                    throw refuseProcessAdmission(workflowName, workflowId, sName, eKind)
                }

            var transportInvoked = false
            val transport =
                FakeHttpTransport { _ ->
                    transportInvoked = true
                    FakeResponse(200, "OK")
                }

            val step = testHttpStep()
            val request = makeExecutionRequest("init-state", transport)

            assertThatThrownBy {
                runBlocking {
                    withContext(testScope(admission, store)) {
                        step.execute(request)
                    }
                }
            }.isInstanceOf(WorkflowAdmissionRefusedException::class.java)

            assertThat(transportInvoked).isFalse()
            assertThat(store.listEvidence(workflowName, workflowId)).isEmpty()
        }
    }

    @Test
    fun `cancellation during transport dispatch transitions evidence to UNCERTAIN`() {
        runBlocking {
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            var sequenceCounter = 0L
            val admission =
                WorkflowHttpAdmission { sName, eKind ->
                    WorkflowHttpPermit(
                        stepName = sName,
                        effectKind = eKind,
                        admissionSequence = ++sequenceCounter,
                        checkpointGeneration = "gen-1",
                    )
                }

            val barrier = CompletableDeferred<Unit>()
            val transport =
                FakeHttpTransport { _ ->
                    barrier.complete(Unit)
                    // simulate hung or cancelled call
                    throw CancellationException("Step cancelled by worker shutdown")
                }

            val step = testHttpStep(url = "http://127.0.0.1/hang")
            val request = makeExecutionRequest("init-state", transport)

            assertThatThrownBy {
                runBlocking {
                    withContext(testScope(admission, store)) {
                        step.execute(request)
                    }
                }
            }.isInstanceOf(CancellationException::class.java)

            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(1)
            val record = records.single()
            assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
        }
    }

    @Test
    fun `step retry after failure creates new attempt lineage and distinct admission sequence`() {
        runBlocking {
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            var sequenceCounter = 0L
            val admission =
                WorkflowHttpAdmission { sName, eKind ->
                    WorkflowHttpPermit(
                        stepName = sName,
                        effectKind = eKind,
                        admissionSequence = ++sequenceCounter,
                        checkpointGeneration = "gen-1",
                    )
                }

            var attemptCount = 0
            val transport =
                FakeHttpTransport { _ ->
                    attemptCount++
                    if (attemptCount == 1) {
                        throw java.io.IOException("First attempt drops")
                    } else {
                        FakeResponse(200, "Recovered")
                    }
                }

            val step = testHttpStep()
            val request = makeExecutionRequest("init-state", transport)

            // Attempt 1 fails
            val attempt1 = PersistedStepAttempt(workflowName, workflowId, stepName, "attempt-1")
            assertThatThrownBy {
                runBlocking {
                    withContext(testScope(admission, store, attempt1)) {
                        step.execute(request)
                    }
                }
            }.isInstanceOf(WorkflowHttpException::class.java)

            // Attempt 2 succeeds
            val attempt2 = PersistedStepAttempt(workflowName, workflowId, stepName, "attempt-2")
            withContext(testScope(admission, store, attempt2)) {
                step.execute(request)
            }

            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)

            assertThat(records[0].key.admissionSeq).isEqualTo(1L)
            assertThat(records[0].stepAttemptId).isEqualTo("attempt-1")
            assertThat(records[0].state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)

            assertThat(records[1].key.admissionSeq).isEqualTo(2L)
            assertThat(records[1].stepAttemptId).isEqualTo("attempt-2")
            assertThat(records[1].state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        }
    }

    @Test
    fun `missing evidence sequence is never inferred as NOT_DISPATCHED or completed`() {
        runBlocking {
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            // Suppose an admission seq was issued (seq=7), but process crashed before child evidence write
            val classification = store.classifyAdmission(workflowName, workflowId, "gen-1", 7L)
            assertThat(classification).isEqualTo(WorkflowEffectClassification.Unclassified)
        }
    }

    @Test
    fun `ordinary ungoverned execution without evidence store runs normally with zero overhead`() {
        runBlocking {
            val transport = FakeHttpTransport { _ -> FakeResponse(200, "Ungoverned OK") }
            val step = testHttpStep()
            val request = makeExecutionRequest("init-state", transport)

            // Running in empty context (no admission, no evidence store, no attempt)
            val result = step.execute(request)
            assertThat(result).isInstanceOf(WorkflowStepExecutionResult.Completed::class.java)
        }
    }

    @Test
    fun `evidence state is ADMITTED and not DISPATCHED while transport send is in flight`() {
        runBlocking {
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            var sequenceCounter = 0L
            val admission =
                WorkflowHttpAdmission { sName, eKind ->
                    WorkflowHttpPermit(
                        stepName = sName,
                        effectKind = eKind,
                        admissionSequence = ++sequenceCounter,
                        checkpointGeneration = "gen-1",
                    )
                }

            val observedDuringSend = AtomicReference<WorkflowEffectEvidenceState?>()
            val transport =
                FakeHttpTransport { _ ->
                    val current =
                        runBlocking {
                            store.getEvidence(WorkflowEffectEvidenceKey(workflowName, workflowId, "gen-1", 1L))
                        }
                    observedDuringSend.set(current?.state)
                    FakeResponse(200, "OK")
                }

            val step = testHttpStep()
            val request = makeExecutionRequest("init-state", transport)

            withContext(testScope(admission, store)) {
                step.execute(request)
            }

            assertThat(observedDuringSend.get()).isEqualTo(WorkflowEffectEvidenceState.ADMITTED)
            val finalRecord = store.listEvidence(workflowName, workflowId).single()
            assertThat(finalRecord.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        }
    }

    @Test
    fun `missing step attempt lineage when evidence store is enabled fails closed before admitting effect`() {
        runBlocking {
            val store = InMemoryWorkflowEffectEvidenceRecordStore()
            var admitCalls = 0
            val admission =
                WorkflowHttpAdmission { sName, eKind ->
                    admitCalls++
                    WorkflowHttpPermit(
                        stepName = sName,
                        effectKind = eKind,
                        admissionSequence = admitCalls.toLong(),
                        checkpointGeneration = "gen-1",
                    )
                }

            var transportInvoked = false
            val transport =
                FakeHttpTransport { _ ->
                    transportInvoked = true
                    FakeResponse(200, "OK")
                }

            val step = testHttpStep()
            val request = makeExecutionRequest("init-state", transport)

            assertThatThrownBy {
                runBlocking {
                    withContext(testScope(admission = admission, evidenceStore = store, attempt = null)) {
                        step.execute(request)
                    }
                }
            }.isInstanceOf(WorkflowEvidenceResolutionException::class.java)
                .hasMessageContaining("authoritative step-attempt lineage or admission authority is absent")

            assertThat(admitCalls).isEqualTo(0)
            assertThat(transportInvoked).isFalse()
            assertThat(store.listEvidence(workflowName, workflowId)).isEmpty()
        }
    }

    @Test
    fun `direct governed workflow with evidence enabled but no attempt authority fails closed before HTTP admission`() {
        runBlocking {
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
            checkpointStore.evidenceStore = evidenceStore

            var transportInvoked = false
            val transport =
                FakeHttpTransport { _ ->
                    transportInvoked = true
                    FakeResponse(200, "OK")
                }

            val wf =
                workflow<String>(workflowName) {
                    outboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth(allowPrivateDestinations = true)
                    httpTransport = transport
                    httpStep(
                        name = stepName,
                        config = HttpStepConfig(allowedHosts = setOf("127.0.0.1")),
                        request = { _, _ -> HttpRequest("GET", "http://127.0.0.1/data") },
                        merge = { s, _, _ -> s },
                    )
                }.build { it }

            assertThatThrownBy {
                runBlocking {
                    wf.run(
                        initialState = "init",
                        run = GovernedRun(WorkflowContext(workflowId), GOVERNED_IDENTITY),
                        observer = NoOpWorkflowObserver,
                        persistence = WorkflowPersistence(checkpointStore = checkpointStore, stateCodec = PlainStringCodec),
                    )
                }
            }.isInstanceOf(WorkflowEvidenceResolutionException::class.java)

            assertThat(transportInvoked).isFalse()
            assertThat(evidenceStore.listEvidence(workflowName, workflowId)).isEmpty()
        }
    }

    @Test
    fun `throwing evidence store on transport failure is attached as suppressed without masking primary transport failure`() {
        runBlocking {
            val delegateStore = InMemoryWorkflowEffectEvidenceRecordStore()
            val failingUpdateStore =
                object : WorkflowEffectEvidenceRecordStore by delegateStore {
                    override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                        error("Simulated evidence DB write outage")
                }

            var sequenceCounter = 0L
            val admission =
                WorkflowHttpAdmission { sName, eKind ->
                    WorkflowHttpPermit(
                        stepName = sName,
                        effectKind = eKind,
                        admissionSequence = ++sequenceCounter,
                        checkpointGeneration = "gen-1",
                    )
                }

            val transport =
                FakeHttpTransport { _ ->
                    throw java.net.http.HttpTimeoutException("Upstream timed out")
                }

            var capturedDiagnostic: WorkflowStepFailureDiagnosticEvent? = null
            val step = testHttpStep()
            val request =
                WorkflowStepExecutionRequest(
                    workflowName = workflowName,
                    state = "init",
                    context = WorkflowContext(workflowId),
                    observer = NoOpWorkflowObserver,
                    stepCounter = StepCounter(StopPolicy()),
                    persistenceSession = null,
                    topLevelStepIndex = 0,
                    resumedCheckpointMetadata = null,
                    services =
                        WorkflowStepExecutionServices(
                            clock = Clock.systemUTC(),
                            httpTransport = transport,
                            outboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth(allowPrivateDestinations = true),
                            externalStepExecutorResolver = NoOpExternalStepExecutorResolver,
                            failureDiagnosticObserver = { event -> capturedDiagnostic = event },
                        ),
                    executeNestedSteps = { _, s -> s },
                )

            assertThatThrownBy {
                runBlocking {
                    withContext(testScope(admission, failingUpdateStore)) {
                        step.execute(request)
                    }
                }
            }.isInstanceOf(WorkflowHttpException::class.java)
                .extracting { (it as WorkflowHttpException).failureCode }
                .isEqualTo(WorkflowStepFailureCode.TIMEOUT)

            assertThat(capturedDiagnostic).isNotNull
            val diagnostic = checkNotNull(capturedDiagnostic)
            assertThat(diagnostic.failure).isInstanceOf(java.net.http.HttpTimeoutException::class.java)
            assertThat(diagnostic.failure?.suppressed?.map { it.message })
                .contains("Simulated evidence DB write outage")
        }
    }

    @Test
    fun `real governed worker persists step attempt lineage and correlates JDBC HTTP evidence end to end`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_e2e_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val fixture = createJdbcWorkerFixture(pool)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            fixture.supervisor.attachScope(scope)

            runBlocking {
                val initialCheckpoint =
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
                val lease =
                    fixture.leaseStore.claim(workflowName, workflowId, "worker-e2e-1", initialCheckpoint.revision, 5_000)
                fixture.supervisor.launch(initialCheckpoint, lease)
                val expectedGeneration = checkNotNull(initialCheckpoint.checkpointGeneration)

                withTimeout(5_000) {
                    while (fixture.supervisor.activeExecutionCount() != 0) delay(10)
                }

                assertThat(fixture.supervisor.latestFailure(workflowId)).isNull()
                val persistedAttempt = fixture.attemptStore.listStepAttempts(workflowId).single()
                assertThat(persistedAttempt.attemptId).isEqualTo("attempt-e2e-1")
                assertThat(persistedAttempt.status).isEqualTo(StepAttemptStatus.COMPLETED)

                val evidence = fixture.evidenceStore.listEvidence(workflowName, workflowId).single()
                assertThat(evidence.key.admissionSeq).isEqualTo(2L)
                assertThat(evidence.key.checkpointGeneration).isEqualTo(expectedGeneration)
                assertThat(evidence.stepName).isEqualTo(stepName)
                assertThat(evidence.stepAttemptId).isEqualTo(persistedAttempt.attemptId)
                assertThat(evidence.effectKind).isEqualTo(HTTP_EFFECT_KIND)
                assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                assertThat(evidence.completedAtEpochMillis).isNotNull()

                val stepAdmissionClassification =
                    fixture.evidenceStore.classifyAdmission(workflowName, workflowId, expectedGeneration, 1L)
                assertThat(stepAdmissionClassification).isEqualTo(WorkflowEffectClassification.Unclassified)
            }
            scope.cancel()
        } finally {
            pool.dispose()
        }
    }

    private fun createJdbcWorkerFixture(pool: JdbcConnectionPool): JdbcWorkerFixture {
        val checkpointStore = JdbcWorkflowCheckpointStore(pool)
        val leaseStore = JdbcWorkflowLeaseStore(pool)
        val attemptStore = JdbcStepAttemptRecordStore(pool)
        val evidenceStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
        checkpointStore.evidenceStore = evidenceStore

        pool.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val statements =
                    listOf(checkpointStore.createTableSql(), leaseStore.createTableSql()) +
                        attemptStore.createSchemaSql() +
                        evidenceStore.createSchemaSql()
                statements.forEach(stmt::execute)
            }
        }

        val transport = FakeHttpTransport { _ -> FakeResponse(200, "E2E-OK") }
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

        val workerConfig =
            WorkerConfig(
                workerId = "worker-e2e-1",
                poolName = "e2e-pool",
                pollIntervalMillis = 20,
                leaseDurationMillis = 5_000,
                drainTimeoutMillis = 1_000,
            )
        val supervisor =
            WorkflowExecutionSupervisor(
                config = workerConfig,
                leaseStore = leaseStore,
                checkpointStore = checkpointStore,
                stepAttemptStore = attemptStore,
                workflowBindings =
                    WorkflowBindingRegistry {
                        bind(wf, WorkflowPersistence(checkpointStore, PlainStringCodec, deleteCheckpointOnCompletion = false))
                    },
                observability = NoOpTramaiWorkerObserver,
                leaseCoordinator = LeaseCoordinator(workerConfig, leaseStore, NoOpTramaiWorkerObserver),
                recoveryCoordinator = WorkflowRecoveryCoordinator(leaseStore, attemptStore),
                leaseRenewalLoop = LeaseRenewalLoop(workerConfig, leaseStore, NoOpTramaiWorkerObserver),
                shuttingDownGracefully = { false },
                stepAttemptIdentitySource = StepAttemptIdentitySource { "attempt-e2e-1" },
            )
        return JdbcWorkerFixture(checkpointStore, leaseStore, attemptStore, evidenceStore, wf, supervisor)
    }

    private class JdbcWorkerFixture(
        val checkpointStore: JdbcWorkflowCheckpointStore,
        val leaseStore: JdbcWorkflowLeaseStore,
        val attemptStore: JdbcStepAttemptRecordStore,
        val evidenceStore: JdbcWorkflowEffectEvidenceRecordStore,
        val workflow: Workflow<String, String>,
        val supervisor: WorkflowExecutionSupervisor,
    )

    private fun makeExecutionRequest(
        state: String,
        transport: HttpTransport,
    ): WorkflowStepExecutionRequest<String> =
        WorkflowStepExecutionRequest(
            workflowName = workflowName,
            state = state,
            context = WorkflowContext(workflowId),
            observer = NoOpWorkflowObserver,
            stepCounter = StepCounter(StopPolicy()),
            persistenceSession = null,
            topLevelStepIndex = 0,
            resumedCheckpointMetadata = null,
            services =
                WorkflowStepExecutionServices(
                    clock = Clock.systemUTC(),
                    httpTransport = transport,
                    outboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth(allowPrivateDestinations = true),
                    externalStepExecutorResolver = NoOpExternalStepExecutorResolver,
                    failureDiagnosticObserver = NoOpWorkflowStepFailureDiagnosticObserver,
                ),
            executeNestedSteps = { _, s -> s },
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
            val response = handler(httpRequest)
            return ControlledSendResult(response)
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
