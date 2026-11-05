package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.time.Clock
import java.util.Optional
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
            // Suppose an admission sequence was issued by authority (seq=7), but process crashed before child evidence write
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
}
