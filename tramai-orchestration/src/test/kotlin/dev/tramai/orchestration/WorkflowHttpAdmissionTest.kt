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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E1-E2: every instrumented HTTP attempt takes its own durable, single-use grant.
 *
 * **One admission point, not two.** `JdkHttpTransport.send` (E1) is reachable only through
 * `HttpWorkflowStep.executeRequest`'s `execution.transport.send` (E2): `HttpTransport` is internal, the only production
 * caller of it is E2, and the only production constructor of the JDK transport is the runner's. Admitting at E2
 * therefore covers the default transport, every internal transport implementation, and the JDK send behind them,
 * without a second grant for one attempt. The last case in this file asserts that topology from the sources, so
 * re-introducing an admission at E1 fails a test rather than quietly double-counting.
 *
 * **Determinism.** Ordering is forced by a store decorator that commits a cancellation immediately after the n-th
 * granted admission -- never by sleeps. In these single-step fixtures admission #1 is the step fence (E11) and #2 the
 * first HTTP attempt; the retry fixtures ask once per attempt after that. The transport is a fake that counts its own
 * invocations, so "no dispatch" is a zero on a counter rather than an absent network trace.
 */
class WorkflowHttpAdmissionTest {
    @Test
    fun `an http permit is consumed exactly once`() {
        val permit = WorkflowHttpPermit(stepName = "fetch", effectKind = HTTP_EFFECT_KIND, admissionSequence = 7L)

        assertTrue(permit.tryConsume(), "the first consumption authorizes one attempt")

        val reuse = assertFailsWith<IllegalStateException> { permit.tryConsume() }
        assertTrue(
            reuse.message!!.contains("already consumed"),
            "reuse must name the programming error, not silently re-send: ${reuse.message}",
        )
    }

    @Test
    fun `an ordinary run sends without asking any authority`() {
        val transport = CountingTransport()
        val store = HttpAdmissionStore(InMemoryWorkflowCheckpointStore())

        val result =
            runBlocking {
                httpWorkflow(transport).run(
                    "s",
                    WorkflowContext(workflowId = "run-1"),
                    NoOpWorkflowObserver,
                    httpPersistence(store),
                )
            }

        assertEquals("s:200", result, "the request really ran")
        assertEquals(1, transport.invocations, "an ordinary run still dispatches")
        assertEquals(0, store.admitCount, "an ungoverned run must ask no authority at all")
    }

    @Test
    fun `one governed request takes exactly one http grant`() {
        val transport = CountingTransport()
        val store = HttpAdmissionStore(InMemoryWorkflowCheckpointStore())

        val result =
            runBlocking {
                httpWorkflow(transport).run("s", httpGoverned(), NoOpWorkflowObserver, httpPersistence(store))
            }

        assertEquals("s:200", result)
        assertEquals(1, transport.invocations, "one request is one dispatch")
        assertEquals(2, store.admitCount, "the step fence and the http fence, and nothing else")
        assertEquals(
            2,
            store.grantedSequences.distinct().size,
            "each grant carries its own sequence: ${store.grantedSequences}",
        )
    }

    @Test
    fun `cancellation before the http admission prevents the send`() {
        val transport = CountingTransport()
        // Admission #1 is the step fence; the cancellation commits straight after it, so the http fence is the one that
        // has to refuse.
        val store = HttpAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 1)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    httpWorkflow(transport).run("s", httpGoverned(), NoOpWorkflowObserver, httpPersistence(store))
                }
            }

        assertEquals(0, transport.invocations, "a refused attempt must not reach the transport")
        assertTrue(
            error.message!!.contains("cannot dispatch $HTTP_EFFECT_KIND for step 'fetch'"),
            "the refusal names the effect and the step: ${error.message}",
        )
        assertEquals(2, store.admitCount, "the step was admitted, then the request asked and was refused")
    }

    @Test
    fun `an http admission before cancellation still sends and leaves the run uncertain`() {
        val transport = CountingTransport()
        // Admission #2 is this request's grant; the cancellation commits after it, so the send is already authorized.
        val store = HttpAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 2)

        val error =
            assertFailsWith<WorkflowCheckpointConflictException> {
                runBlocking {
                    httpWorkflow(transport).run("s", httpGoverned(), NoOpWorkflowObserver, httpPersistence(store))
                }
            }

        assertEquals(1, transport.invocations, "an admitted attempt dispatches even if cancellation commits next")
        assertTrue(error.message!!.isNotBlank(), "the terminal-save conflict is reported, not swallowed")
        assertEquals(
            WorkflowLifecycleStatus.CANCELLED_UNCERTAIN,
            runBlocking { store.lifecycle("checkout", "run-1") }?.status,
            "an admitted effect makes the terminal outcome uncertain, never a clean CANCELLED",
        )
    }

    @Test
    fun `a retry takes a fresh grant for every attempt`() {
        val transport = CountingTransport(statuses = listOf(503, 200))
        val store = HttpAdmissionStore(InMemoryWorkflowCheckpointStore())

        val result =
            runBlocking {
                httpWorkflow(transport, retryConfig())
                    .run("s", httpGoverned(), NoOpWorkflowObserver, httpPersistence(store))
            }

        assertEquals("s:200", result, "the retry is what makes the run succeed")
        assertEquals(2, transport.invocations, "two attempts, two sends")
        assertEquals(3, store.admitCount, "one step fence plus one grant per attempt")
        assertEquals(
            3,
            store.grantedSequences.distinct().size,
            "a retry is a new decision point, so it gets a new sequence: ${store.grantedSequences}",
        )
    }

    @Test
    fun `cancellation between attempts prevents the next send`() {
        val transport = CountingTransport(statuses = listOf(503, 200))
        // Admission #2 is the first attempt's grant; cancellation commits after it, before the retry asks again.
        val store = HttpAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 2)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    httpWorkflow(transport, retryConfig())
                        .run("s", httpGoverned(), NoOpWorkflowObserver, httpPersistence(store))
                }
            }

        assertEquals(1, transport.invocations, "the retry never reaches the transport")
        assertTrue(
            error.message!!.contains("cannot dispatch"),
            "the retry is refused by the authority, not failed as a transport error: ${error.message}",
        )
        assertEquals(3, store.admitCount, "the retry asked again and was refused: the retry is a new decision point")
    }

    @Test
    fun `a store that cannot admit refuses the send`() {
        val transport = CountingTransport()
        val store = HttpAdmissionStore(InMemoryWorkflowCheckpointStore(), refuseAdmissionsFromAdmit = 2)

        assertFailsWith<WorkflowCancellationUnsupportedException> {
            runBlocking {
                httpWorkflow(transport).run("s", httpGoverned(), NoOpWorkflowObserver, httpPersistence(store))
            }
        }

        assertEquals(0, transport.invocations, "no durable authority means no dispatch")
    }

    @Test
    fun `a governed run with no durable session never sends`() {
        val transport = CountingTransport()

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                // No persistence at all: the run still claims the durable guarantees, so it has nothing to ask.
                runBlocking {
                    httpWorkflow(transport).run("s", httpGoverned(), NoOpWorkflowObserver, persistence = null)
                }
            }

        assertEquals(0, transport.invocations, "fail-closed, not fail-open")
        assertTrue(
            error.message!!.contains("no durable checkpoint authority"),
            "fail-closed reason: ${error.message}",
        )
    }

    @Test
    fun `a connected-address transport is guarded exactly like the default one`() {
        val transport =
            CountingTransport(capability = HttpTransportCapability.CONNECTED_ADDRESS_VALIDATION)
        val store = HttpAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 1)

        assertFailsWith<WorkflowAdmissionRefusedException> {
            runBlocking {
                httpWorkflow(transport).run("s", httpGoverned(), NoOpWorkflowObserver, httpPersistence(store))
            }
        }

        assertEquals(0, transport.invocations, "an alternative transport implementation is not a bypass")
    }

    @Test
    fun `only the dispatch site admits, so the jdk send behind it cannot double count`() {
        val sources = mainSourceFiles()
        // The declaration itself lives in WorkflowLifecycle.kt; a *call* is what may only exist once, at the dispatch
        // site, so match the member-access form.
        val admitSites = sources.filter { (_, text) -> text.contains(".admitHttp(") }
        assertEquals(
            listOf("HttpStep.kt"),
            admitSites.map { (file, _) -> file.fileName.toString() },
            "exactly one admission site, and it is the dispatch site",
        )
        assertEquals(
            1,
            admitSites
                .single()
                .second
                .windowed(".admitHttp(".length)
                .count { it == ".admitHttp(" },
            "one admission call per attempt, never one per boundary",
        )

        val transportCallers =
            sources.filter { (file, text) ->
                file.fileName.toString() != "HttpStep.kt" && text.contains(".transport.send(")
            }
        assertTrue(
            transportCallers.isEmpty(),
            "HttpWorkflowStep must stay the only production caller of HttpTransport.send, or E1 becomes reachable " +
                "without E2: ${transportCallers.map { it.first.fileName }}",
        )

        val jdkTransport = sources.single { (file, _) -> file.fileName.toString() == "HttpStep.kt" }.second
        val jdkSendBody =
            jdkTransport.substringAfter("internal class JdkHttpTransport").substringBefore("internal data class")
        assertTrue(
            !jdkSendBody.contains(".admitHttp(") && !jdkSendBody.contains(".admitProcess("),
            "E1 dispatches under the grant E2 took for the same attempt; a second admission here double counts",
        )
    }
}

private fun retryConfig(): HttpStepConfig =
    HttpStepConfig(
        timeoutSeconds = 30,
        retryOnStatus = setOf(503),
        maxRetries = 1,
        allowedHosts = setOf("127.0.0.1"),
    )

private fun httpWorkflow(
    transport: HttpTransport,
    config: HttpStepConfig = HttpStepConfig(timeoutSeconds = 30, allowedHosts = setOf("127.0.0.1")),
): Workflow<String, String> =
    workflow<String>("checkout") {
        outboundNetworkPolicy = OutboundNetworkPolicies.defenceInDepth(allowPrivateDestinations = true)
        httpTransport = transport
        httpStep(
            name = "fetch",
            config = config,
            request = { _, _ -> HttpRequest(method = "GET", url = "http://127.0.0.1:9/status") },
            merge = { state, response, _ -> "$state:${response.status}" },
        )
    }.build { it }

private fun httpPersistence(store: WorkflowCheckpointStore): WorkflowPersistence<String> =
    WorkflowPersistence(checkpointStore = store, stateCodec = HttpPlainCodec)

private fun httpGoverned(): GovernedRun = GovernedRun(WorkflowContext(workflowId = "run-1"), HTTP_GOVERNED)

private suspend fun cancelHttpRun(store: WorkflowCheckpointStore) {
    val current = store.load("checkout", "run-1") ?: return
    store.cancel(
        WorkflowCancellationRequest(
            workflowName = "checkout",
            workflowId = current.workflowId,
            expectedRevision = current.revision,
            expectedGeneration = current.checkpointGeneration,
            actor = "operator",
            reason = "stopped",
            cancelledAtEpochMillis = 0L,
        ),
    )
}

/**
 * A transport that never touches the network: it returns canned statuses in order and counts how often it was invoked.
 *
 * The counter is the evidence for every "did not dispatch" claim in this file.
 */
private class CountingTransport(
    private val statuses: List<Int> = listOf(200),
    override val capability: HttpTransportCapability = HttpTransportCapability.PRE_CONNECT_VALIDATION,
) : HttpTransport {
    private var sent = 0

    val invocations: Int
        get() = sent

    override suspend fun send(
        httpRequest: java.net.http.HttpRequest,
        blockingDispatcher: kotlin.coroutines.CoroutineContext,
        onConnected: (InetAddress) -> Unit,
    ): ControlledSendResult {
        val status = statuses.getOrElse(sent) { statuses.last() }
        sent += 1
        onConnected(InetAddress.getByName("127.0.0.1"))
        return ControlledSendResult(fakeHttpResponse(httpRequest, status))
    }
}

private fun fakeHttpResponse(
    request: java.net.http.HttpRequest,
    status: Int,
    body: InputStream = ByteArrayInputStream(ByteArray(0)),
): java.net.http.HttpResponse<InputStream> =
    object : java.net.http.HttpResponse<InputStream> {
        override fun statusCode() = status

        override fun request() = request

        override fun previousResponse(): Optional<java.net.http.HttpResponse<InputStream>> = Optional.empty()

        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body(): InputStream = body

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri(): URI = request.uri()

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

/**
 * Decorates the real store to force an admission ordering and to count what the runtime asked for.
 *
 * [cancelAfterAdmit] commits a cancellation immediately after that granted admission, which is how "the step was
 * admitted but the request was not" and "the request was admitted and cancellation followed" become deterministic.
 * [refuseAdmissionsFromAdmit] makes the store itself refuse from that admission on, standing in for a profile that
 * cannot decide admission at all.
 */
private class HttpAdmissionStore(
    private val delegate: WorkflowCheckpointStore,
    private val cancelAfterAdmit: Int? = null,
    private val refuseAdmissionsFromAdmit: Int? = null,
) : WorkflowCheckpointStore by delegate {
    private val mutex = Mutex()
    private var attempts = 0
    private val granted = mutableListOf<Long>()

    val admitCount: Int
        get() = attempts

    val grantedSequences: List<Long>
        get() = granted

    override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission =
        mutex.withLock {
            attempts += 1
            if (refuseAdmissionsFromAdmit != null && attempts >= refuseAdmissionsFromAdmit) {
                throw WorkflowCancellationUnsupportedException(
                    "${delegate::class.simpleName} cannot admit in this test",
                )
            }
            val admission = delegate.admit(request)
            if (admission.admitted) {
                granted += admission.sequence
                if (cancelAfterAdmit == attempts) {
                    cancelHttpRun(delegate)
                }
            }
            admission
        }
}

private object HttpPlainCodec : WorkflowStateCodec<String> {
    override fun encode(state: String): String = state

    override fun decode(payload: String): String = payload
}

private val HTTP_GOVERNED =
    GovernedRunIdentity(
        deployment =
            WorkloadDeploymentIdentity(
                workloadId = WorkloadId("checkout"),
                configuration = WorkloadConfigurationIdentity(ConfigurationId("conf"), ConfigurationVersion("1")),
                environmentId = EnvironmentId("test"),
                deploymentId = DeploymentId("dep"),
            ),
        runId = RunId("run-1"),
    )

/** Every orchestration main source file, keyed by path, for the topology assertions. */
private fun mainSourceFiles(): List<Pair<Path, String>> {
    val candidates =
        listOf(
            Path.of("src/main/kotlin/dev/tramai/orchestration"),
            Path.of("tramai-orchestration/src/main/kotlin/dev/tramai/orchestration"),
        )
    val root =
        candidates.firstOrNull { Files.isDirectory(it) }
            ?: fail("none of the orchestration source roots exist: $candidates")
    return Files.list(root).use { stream ->
        stream
            .filter { it.fileName.toString().endsWith(".kt") }
            .map { it to Files.readString(it) }
            .toList()
    }
}

private fun fail(message: String): Nothing = throw AssertionError(message)
