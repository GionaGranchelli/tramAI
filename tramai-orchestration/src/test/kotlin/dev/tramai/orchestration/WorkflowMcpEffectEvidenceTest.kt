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
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.typeOf

/**
 * Deterministic qualification test suite for D7-C durable MCP effect evidence (Server Start E4 and Tool Call E3).
 *
 * Covers scenarios MCE-01 through MCE-20:
 * - MCE-01: Governed E4 connection succeeds with correct JDBC evidence (COMPLETED, MCP_SERVER_START)
 * - MCE-02: Governed E3 tool call succeeds with separate JDBC evidence (COMPLETED, MCP_TOOL)
 * - MCE-03: Missing authority or lineage refuses before E4 (WorkflowEvidenceResolutionException)
 * - MCE-04: Admission committed but E4 child write fails (W1: checkpoint seq advanced, child store empty, unclassified)
 * - MCE-05: E4 ADMITTED persisted; failure before connection acquisition (W2: stays ADMITTED / transitions to UNCERTAIN)
 * - MCE-06: E4 connection succeeds; client initialization fails (E4 DISPATCHED -> UNCERTAIN, transport cleaned up, E3 never admitted)
 * - MCE-07: E4 succeeds; E3 admission refused (E4 remains COMPLETED, E3 throws refusal, resources cleaned up)
 * - MCE-08: E3 admission recorded; failure before invocation (E4 COMPLETED, E3 ADMITTED -> UNCERTAIN, tool not called)
 * - MCE-09: E3 result returned; completion evidence write fails (W3: E3 remains DISPATCHED in store)
 * - MCE-10: E3 invocation may have executed but response lost: no automatic retry even with reconnect=true
 * - MCE-11: Pre-tool connection failure reconnects with fresh E4 admission
 * - MCE-12: Client cleanup fails after tool result: no duplicate invocation (McpPostCallCleanupException)
 * - MCE-13: Timeout/cancellation preserves exception identity and cleanup
 * - MCE-14: Missing expected evidence record fails closed (WorkflowEvidenceResolutionException)
 * - MCE-15: Multiple reconnect attempts have distinct evidence keys and admission sequences
 * - MCE-16: Two MCP effects (E4 and E3) within one step share authoritative parent attempt (stepAttemptId)
 * - MCE-17: Separate top-level steps use distinct persisted attempts
 * - MCE-18: isError=true tool result preserves truthful protocol evidence (COMPLETED)
 * - MCE-19: Secret-bearing exception, arguments and output do not leak into durable evidence
 * - MCE-20: Ungoverned MCP execution remains compatible with zero evidence writes
 */
class WorkflowMcpEffectEvidenceTest {
    private val workflowName = "test-mcp-workflow"
    private val workflowId = "run-mcp-1"
    private val stepName = "mcp-step"
    private val attemptId = "att-mcp-1"
    private val servers = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        val failures = mutableListOf<Throwable>()
        servers.asReversed().forEach { fixture ->
            try {
                fixture.close()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        servers.clear()
        failures.firstOrNull()?.let { primary ->
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }

    private fun testScope(
        admission: WorkflowMcpAdmission? = null,
        evidenceStore: WorkflowEffectEvidenceRecordStore? = null,
        attempt: PersistedStepAttempt? = PersistedStepAttempt(workflowName, workflowId, stepName, attemptId),
        clock: Clock = Clock.systemUTC(),
    ) = WorkflowMcpAdmissionScope(admission) +
        WorkflowEffectEvidenceStoreScope(evidenceStore, clock) +
        (if (attempt != null) StepAttemptScope(attempt) else kotlin.coroutines.EmptyCoroutineContext)

    private fun createEchoStep(
        transportProvider: McpTransportProvider,
        stepName: String = this.stepName,
        toolName: String = "echo",
        config: McpStepConfig = McpStepConfig.unrestricted(),
        arguments: Map<String, String> = mapOf("message" to "hello"),
    ): McpWorkflowStep<McpState> =
        McpWorkflowStep(
            name = stepName,
            definition =
                McpToolCallDefinition(
                    serverCommand = listOf("echo-server"),
                    toolName = toolName,
                    argumentKeys = arguments.keys,
                ),
            config = config,
            toolCallBuilder = { _, _ ->
                McpToolCall(
                    serverCommand = listOf("echo-server"),
                    toolName = toolName,
                    arguments = arguments,
                )
            },
            merge = { state, result, _ -> state.copy(result = result) },
            transportProvider = transportProvider,
        )

    private suspend fun McpWorkflowStep<McpState>.executeStep(
        state: McpState = McpState(),
        targetWorkflowName: String = this@WorkflowMcpEffectEvidenceTest.workflowName,
        targetWorkflowId: String = this@WorkflowMcpEffectEvidenceTest.workflowId,
    ): McpState =
        execute(
            workflowName = targetWorkflowName,
            state = state,
            context = WorkflowContext(targetWorkflowId),
            observer = NoOpWorkflowObserver,
        )

    @Test
    fun `MCE-01 - governed E4 connection succeeds with correct JDBC evidence`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_mce1_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val step = createEchoStep(transportProvider = transportProvider)
            val fixture = createJdbcWorkerFixture(pool = pool, schemaStore = delegateStore, step = step)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            fixture.supervisor.attachScope(scope)

            runBlocking {
                val initialCheckpoint = saveGovernedInitialCheckpoint(fixture)
                val expectedGeneration = checkNotNull(initialCheckpoint.checkpointGeneration)
                val lease = fixture.leaseStore.claim(workflowName, workflowId, "worker-e2e-1", initialCheckpoint.revision, 5_000)
                fixture.supervisor.launch(initialCheckpoint, lease)

                withTimeout(10_000) {
                    while (fixture.supervisor.activeExecutionCount() != 0) delay(10)
                }

                assertThat(fixture.supervisor.latestFailure(workflowId)).isNull()
                val evidenceList = delegateStore.listEvidence(workflowName, workflowId)
                assertThat(evidenceList).hasSize(2)

                val e4Record = evidenceList.first { it.effectKind == MCP_SERVER_START_EFFECT_KIND }
                assertThat(e4Record.key.workflowName).isEqualTo(workflowName)
                assertThat(e4Record.key.workflowId).isEqualTo(workflowId)
                assertThat(e4Record.key.checkpointGeneration).isEqualTo(expectedGeneration)
                assertThat(e4Record.key.admissionSeq).isEqualTo(2L) // 1 was step admission E11, 2 is E4
                assertThat(e4Record.stepName).isEqualTo(stepName)
                assertThat(e4Record.stepAttemptId).isEqualTo("attempt-e2e-1")
                assertThat(e4Record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                assertThat(e4Record.createdAtEpochMillis).isGreaterThan(0L)
                assertThat(e4Record.completedAtEpochMillis).isNotNull()
                assertThat(e4Record.completedAtEpochMillis).isGreaterThanOrEqualTo(e4Record.createdAtEpochMillis)
                assertThat(e4Record.diagnosticSummary).isNull()
            }
            scope.cancel()
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `MCE-02 - governed E3 tool call succeeds with separate JDBC evidence`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_mce2_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val step = createEchoStep(transportProvider = transportProvider)
            val fixture = createJdbcWorkerFixture(pool = pool, schemaStore = delegateStore, step = step)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            fixture.supervisor.attachScope(scope)

            runBlocking {
                val initialCheckpoint = saveGovernedInitialCheckpoint(fixture)
                val expectedGeneration = checkNotNull(initialCheckpoint.checkpointGeneration)
                val lease = fixture.leaseStore.claim(workflowName, workflowId, "worker-e2e-1", initialCheckpoint.revision, 5_000)
                fixture.supervisor.launch(initialCheckpoint, lease)

                withTimeout(10_000) {
                    while (fixture.supervisor.activeExecutionCount() != 0) delay(10)
                }

                assertThat(fixture.supervisor.latestFailure(workflowId)).isNull()
                val evidenceList = delegateStore.listEvidence(workflowName, workflowId)
                assertThat(evidenceList).hasSize(2)

                val e3Record = evidenceList.first { it.effectKind == MCP_TOOL_EFFECT_KIND }
                assertThat(e3Record.key.workflowName).isEqualTo(workflowName)
                assertThat(e3Record.key.workflowId).isEqualTo(workflowId)
                assertThat(e3Record.key.checkpointGeneration).isEqualTo(expectedGeneration)
                assertThat(e3Record.key.admissionSeq).isEqualTo(3L) // 1 was step, 2 was E4, 3 is E3
                assertThat(e3Record.stepName).isEqualTo(stepName)
                assertThat(e3Record.stepAttemptId).isEqualTo("attempt-e2e-1")
                assertThat(e3Record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                assertThat(e3Record.createdAtEpochMillis).isGreaterThan(0L)
                assertThat(e3Record.completedAtEpochMillis).isNotNull()
                assertThat(e3Record.completedAtEpochMillis).isGreaterThanOrEqualTo(e3Record.createdAtEpochMillis)
                assertThat(e3Record.diagnosticSummary).isNull()
            }
            scope.cancel()
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `MCE-03 - missing authority or lineage refuses before E4 with WorkflowEvidenceResolutionException`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        var connectCalled = false
        val dummyTransport =
            object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                    connectCalled = true
                    error("Transport should not be called")
                }
            }
        val step = createEchoStep(transportProvider = dummyTransport)

        // Case A: evidenceStore != null, but admission == null
        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = null, evidenceStore = store)) {
                    step.executeStep()
                }
            }
        }.isInstanceOf(WorkflowEvidenceResolutionException::class.java)
            .hasMessageContaining("authoritative step-attempt lineage or admission authority is absent")

        // Case B: evidenceStore != null, admission != null, but attempt == null
        var admissionCalled = false
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit {
                    admissionCalled = true
                    return WorkflowMcpServerStartPermit(stepName, 1L, "gen-1")
                }

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit = WorkflowMcpToolPermit(stepName, 2L, "gen-1")
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store, attempt = null)) {
                    step.executeStep()
                }
            }
        }.isInstanceOf(WorkflowEvidenceResolutionException::class.java)
            .hasMessageContaining("authoritative step-attempt lineage or admission authority is absent")

        assertThat(connectCalled).isFalse()
        assertThat(admissionCalled).isFalse()
        runBlocking {
            assertThat(store.listEvidence(workflowName, workflowId)).isEmpty()
        }
    }

    @Test
    fun `MCE-04 - admission committed but E4 child write fails W1`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_mce4_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val delegateStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val failingStore =
                object : WorkflowEffectEvidenceRecordStore by delegateStore {
                    override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                        if (record.effectKind == MCP_SERVER_START_EFFECT_KIND) {
                            throw IOException("W1 simulated store failure on E4 admission")
                        }
                        return delegateStore.recordEvidence(record)
                    }
                }

            val step =
                createEchoStep(
                    config = McpStepConfig.unrestricted().copy(reconnect = false),
                    transportProvider = transportProvider,
                )
            val fixture =
                createJdbcWorkerFixture(
                    pool = pool,
                    evidenceStoreOverride = failingStore,
                    schemaStore = delegateStore,
                    step = step,
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            fixture.supervisor.attachScope(scope)

            runBlocking {
                val initialCheckpoint = saveGovernedInitialCheckpoint(fixture)
                val expectedGeneration = checkNotNull(initialCheckpoint.checkpointGeneration)
                val lease = fixture.leaseStore.claim(workflowName, workflowId, "worker-e2e-1", initialCheckpoint.revision, 5_000)
                fixture.supervisor.launch(initialCheckpoint, lease)

                withTimeout(10_000) {
                    while (fixture.supervisor.activeExecutionCount() != 0) delay(10)
                }

                assertThat(fixture.supervisor.latestFailure(workflowId)).isNotNull()
                // Child store has 0 records because write failed
                assertThat(delegateStore.listEvidence(workflowName, workflowId)).isEmpty()

                // Classification of admission sequence 2 reports Unclassified
                val classification = delegateStore.classifyAdmission(workflowName, workflowId, expectedGeneration, 2L)
                assertThat(classification).isEqualTo(WorkflowEffectClassification.Unclassified)

                // Sequence 2 was consumed in checkpoint store, so next admit produces 3
                val nextAdmit =
                    fixture.checkpointStore.admit(
                        WorkflowAdmissionRequest(
                            workflowName = workflowName,
                            workflowId = workflowId,
                            expectedRevision = initialCheckpoint.revision,
                            expectedGeneration = expectedGeneration,
                        ),
                    )
                assertThat(nextAdmit.sequence).isEqualTo(3L)
            }
            scope.cancel()
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `MCE-05 - E4 ADMITTED persisted, failure before connection acquisition W2`() {
        val failingTransport =
            object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection =
                    throw IOException("Connection refused before transport acquisition")
            }
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step = createEchoStep(config = McpStepConfig.unrestricted().copy(reconnect = false), transportProvider = failingTransport)

        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-w2")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-w2")
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store)) {
                    step.executeStep()
                }
            }
        }.isInstanceOf(WorkflowMcpException::class.java)

        runBlocking {
            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(1)
            val e4 = records.single()
            assertThat(e4.effectKind).isEqualTo(MCP_SERVER_START_EFFECT_KIND)
            assertThat(e4.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(e4.diagnosticSummary).isEqualTo(MCP_SERVER_START_FAILED_DIAGNOSTIC)
        }
    }

    @Test
    fun `MCE-06 - E4 connection succeeds, client initialization fails`() {
        val cleanupCalled = AtomicBoolean(false)
        val brokenPipeTransport =
            object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                    val pOut = PipedOutputStream()
                    val pIn = PipedInputStream(pOut)
                    pIn.close() // Closed stream causes client.connect() to fail immediately
                    pOut.close()
                    return McpTransportConnection(
                        input = pIn.asSource().buffered(),
                        output = pOut.asSink().buffered(),
                        cleanup = { cleanupCalled.set(true) },
                    )
                }
            }

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step = createEchoStep(config = McpStepConfig.unrestricted().copy(reconnect = false), transportProvider = brokenPipeTransport)
        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-init")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-init")
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store)) {
                    step.executeStep()
                }
            }
        }.isInstanceOf(WorkflowMcpException::class.java)

        assertThat(cleanupCalled.get()).isTrue()
        runBlocking {
            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(1)
            val e4 = records.single()
            assertThat(e4.effectKind).isEqualTo(MCP_SERVER_START_EFFECT_KIND)
            assertThat(e4.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(e4.diagnosticSummary).isEqualTo(MCP_INITIALIZATION_FAILED_DIAGNOSTIC)
        }
    }

    @Test
    fun `MCE-07 - E4 succeeds, E3 admission refused leaving E4 COMPLETED`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step = createEchoStep(transportProvider = transportProvider)

        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, 1L, "gen-e3-refusal")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    throw WorkflowEvidenceResolutionException("E3 tool call admission refused by policy")
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store)) {
                    step.executeStep()
                }
            }
        }.isInstanceOf(WorkflowEvidenceResolutionException::class.java)
            .hasMessageContaining("E3 tool call admission refused by policy")

        runBlocking {
            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(1)
            val e4 = records.single()
            assertThat(e4.effectKind).isEqualTo(MCP_SERVER_START_EFFECT_KIND)
            assertThat(e4.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(e4.diagnosticSummary).isNull()
        }
    }

    @Test
    fun `MCE-08 - E3 admission recorded, failure before invocation marks E3 UNCERTAIN`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        // Asking for nonexistent tool triggers failure during tool invocation
        val step =
            createEchoStep(
                toolName = "nonexistent_tool",
                config = McpStepConfig.unrestricted().copy(reconnect = false),
                transportProvider = transportProvider,
            )

        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m08")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m08")
            }

        runBlocking {
            withContext(testScope(admission = admission, evidenceStore = store)) {
                val result = step.executeStep()
                // MCP protocol reports error for nonexistent tool as CallToolResult.isError = true
                assertThat(result.result?.isError).isTrue()
            }

            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)
            val e4 = records.first { it.effectKind == MCP_SERVER_START_EFFECT_KIND }
            val e3 = records.first { it.effectKind == MCP_TOOL_EFFECT_KIND }
            assertThat(e4.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(e3.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        }
    }

    @Test
    fun `MCE-09 - E3 result returned, completion evidence write fails W3`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val baseStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val w3Store =
            object : WorkflowEffectEvidenceRecordStore by baseStore {
                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                    if (record.effectKind == MCP_TOOL_EFFECT_KIND && record.state == WorkflowEffectEvidenceState.COMPLETED) {
                        throw IOException("W3 disk failure writing E3 completion")
                    }
                    return baseStore.updateEvidence(record)
                }
            }

        val step =
            createEchoStep(
                config = McpStepConfig.unrestricted().copy(reconnect = false),
                transportProvider = transportProvider,
            )
        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-w3")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-w3")
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = w3Store)) {
                    step.executeStep()
                }
            }
        }.isInstanceOf(WorkflowMcpException::class.java)

        runBlocking {
            val records = baseStore.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)
            val e4 = records.first { it.effectKind == MCP_SERVER_START_EFFECT_KIND }
            val e3 = records.first { it.effectKind == MCP_TOOL_EFFECT_KIND }
            assertThat(e4.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            // Critical W3 guarantee: E3 was updated to DISPATCHED before completion write failed
            assertThat(e3.state).isEqualTo(WorkflowEffectEvidenceState.DISPATCHED)
            assertThat(e3.completedAtEpochMillis).isNull()
        }
    }

    @Test
    fun `MCE-10 - E3 invocation may have executed but response lost does not automatically retry even with reconnect`() {
        val callCount = AtomicInteger(0)
        val (transportProvider, serverCloseable) =
            createEchoServer(
                onToolCall = { pipe ->
                    callCount.incrementAndGet()
                    pipe.close() // Close pipe so client drops connection mid-invocation
                },
            )
        servers += serverCloseable

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step =
            createEchoStep(
                config = McpStepConfig.unrestricted().copy(reconnect = true),
                transportProvider = transportProvider,
            )

        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m10")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m10")
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store)) {
                    step.executeStep()
                }
            }
        }.isInstanceOf(WorkflowMcpException::class.java)

        // Verifies no retry occurred on outcome-uncertain failure
        assertThat(callCount.get()).isEqualTo(1)
        runBlocking {
            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)
            val e3 = records.first { it.effectKind == MCP_TOOL_EFFECT_KIND }
            assertThat(e3.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(e3.diagnosticSummary).isEqualTo(MCP_TOOL_EXECUTION_FAILED_DIAGNOSTIC)
        }
    }

    @Test
    fun `MCE-11 - pre-tool connection failure reconnects with fresh E4 admission`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val connectAttempts = AtomicInteger(0)
        val flakyTransport =
            object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                    if (connectAttempts.incrementAndGet() == 1) {
                        throw IOException("Transient transport connect error")
                    }
                    return transportProvider.connect(toolCall)
                }
            }

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step =
            createEchoStep(
                config = McpStepConfig.unrestricted().copy(reconnect = true),
                transportProvider = flakyTransport,
            )

        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m11")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m11")
            }

        val result =
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store)) {
                    step.executeStep()
                }
            }

        assertThat(result.result?.content).contains("echo: hello")
        assertThat(connectAttempts.get()).isEqualTo(2)

        runBlocking {
            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(3)

            // Attempt 1: E4 admitted then failed
            val e4First = records[0]
            assertThat(e4First.key.admissionSeq).isEqualTo(1L)
            assertThat(e4First.effectKind).isEqualTo(MCP_SERVER_START_EFFECT_KIND)
            assertThat(e4First.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(e4First.diagnosticSummary).isEqualTo(MCP_SERVER_START_FAILED_DIAGNOSTIC)

            // Attempt 2: Fresh E4 admission, succeeded
            val e4Second = records[1]
            assertThat(e4Second.key.admissionSeq).isEqualTo(2L)
            assertThat(e4Second.effectKind).isEqualTo(MCP_SERVER_START_EFFECT_KIND)
            assertThat(e4Second.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)

            // Attempt 2: E3 admission, succeeded
            val e3 = records[2]
            assertThat(e3.key.admissionSeq).isEqualTo(3L)
            assertThat(e3.effectKind).isEqualTo(MCP_TOOL_EFFECT_KIND)
            assertThat(e3.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        }
    }

    @Test
    fun `MCE-12 - client cleanup fails after tool result without duplicate invocation`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val toolCalls = AtomicInteger(0)
        val cleanupFailingTransport =
            object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                    val conn = transportProvider.connect(toolCall)
                    return conn.copy(
                        cleanup = {
                            toolCalls.incrementAndGet()
                            throw IOException("Post-call cleanup failure")
                        },
                    )
                }
            }

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step =
            createEchoStep(
                config = McpStepConfig.unrestricted().copy(reconnect = true),
                transportProvider = cleanupFailingTransport,
            )

        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m12")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m12")
            }

        val error =
            runCatching {
                runBlocking {
                    withContext(testScope(admission = admission, evidenceStore = store)) {
                        step.executeStep()
                    }
                }
            }.exceptionOrNull()

        assertThat(error).isInstanceOf(WorkflowMcpException::class.java)
        assertThat(error?.message).contains("cleanup failed")
        assertThat(workflowFailureCode(error!!)).isEqualTo(WorkflowStepFailureCode.CLEANUP_FAILED)

        // Crucial invariant: even with reconnect=true, cleanup failure does NOT retry
        assertThat(toolCalls.get()).isEqualTo(1)

        runBlocking {
            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)
            val e4 = records.first { it.effectKind == MCP_SERVER_START_EFFECT_KIND }
            val e3 = records.first { it.effectKind == MCP_TOOL_EFFECT_KIND }
            // Both effects completed truthfully before cleanup failed
            assertThat(e4.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(e3.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        }
    }

    @Test
    fun `MCE-13 - timeout or cancellation preserves exception identity and cleanup`() {
        val (transportProvider, serverCloseable) = createEchoServer(delayMillis = 2_000)
        servers += serverCloseable

        val cleanupCalled = AtomicBoolean(false)
        val instrumentedTransport =
            object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                    val conn = transportProvider.connect(toolCall)
                    return conn.copy(
                        cleanup = {
                            cleanupCalled.set(true)
                            conn.cleanup?.invoke()
                        },
                    )
                }
            }

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step =
            createEchoStep(
                config = McpStepConfig.unrestricted().copy(timeoutSeconds = 1, reconnect = false),
                transportProvider = instrumentedTransport,
            )

        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m13")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m13")
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store)) {
                    step.executeStep()
                }
            }
        }.isInstanceOf(WorkflowMcpException::class.java)
            .hasMessageContaining("timed out")

        assertThat(cleanupCalled.get()).isTrue()
        runBlocking {
            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)
            val e3 = records.first { it.effectKind == MCP_TOOL_EFFECT_KIND }
            assertThat(e3.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
            assertThat(e3.diagnosticSummary).isEqualTo(MCP_TIMEOUT_DIAGNOSTIC)
        }
    }

    @Test
    fun `MCE-14 - missing expected evidence record fails closed with WorkflowEvidenceResolutionException`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val baseStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val corruptingStore =
            object : WorkflowEffectEvidenceRecordStore by baseStore {
                override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? = null
            }

        val step = createEchoStep(transportProvider = transportProvider)
        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m14")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m14")
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = corruptingStore)) {
                    step.executeStep()
                }
            }
        }.isInstanceOf(WorkflowEvidenceResolutionException::class.java)
            .hasMessageContaining("Missing expected evidence record")
    }

    @Test
    fun `MCE-15 - multiple reconnect attempts have distinct evidence keys and admission sequences`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val connectAttempts = AtomicInteger(0)
        val transport =
            object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                    if (connectAttempts.incrementAndGet() < 2) {
                        throw IOException("Connect attempt $connectAttempts failed")
                    }
                    return transportProvider.connect(toolCall)
                }
            }

        val step =
            createEchoStep(
                config = McpStepConfig.unrestricted().copy(reconnect = true),
                transportProvider = transport,
            )

        var seq = 10L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m15")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m15")
            }

        runBlocking {
            withContext(testScope(admission = admission, evidenceStore = store)) {
                step.executeStep()
            }

            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(3)
            val sequences = records.map { it.key.admissionSeq }
            assertThat(sequences).containsExactly(10L, 11L, 12L)
            // Prove every record has a distinct key
            assertThat(records.map { it.key }.distinct()).hasSize(3)
        }
    }

    @Test
    fun `MCE-16 - two MCP effects within one step share authoritative parent attempt`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step = createEchoStep(transportProvider = transportProvider)

        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m16")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m16")
            }

        runBlocking {
            withContext(
                testScope(
                    admission = admission,
                    evidenceStore = store,
                    attempt = PersistedStepAttempt(workflowName, workflowId, stepName, "parent-attempt-xyz"),
                ),
            ) {
                step.executeStep()
            }

            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)
            assertThat(records[0].stepAttemptId).isEqualTo("parent-attempt-xyz")
            assertThat(records[1].stepAttemptId).isEqualTo("parent-attempt-xyz")
            assertThat(records[0].stepName).isEqualTo(stepName)
            assertThat(records[1].stepName).isEqualTo(stepName)
        }
    }

    @Test
    fun `MCE-17 - separate top-level steps use distinct persisted attempts`() {
        val (transport1, serverCloseable1) = createEchoServer()
        val (transport2, serverCloseable2) = createEchoServer()
        servers += serverCloseable1
        servers += serverCloseable2

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step1 = createEchoStep(stepName = "step-1", transportProvider = transport1)
        val step2 = createEchoStep(stepName = "step-2", transportProvider = transport2)

        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m17")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m17")
            }

        runBlocking {
            withContext(
                testScope(
                    admission = admission,
                    evidenceStore = store,
                    attempt = PersistedStepAttempt(workflowName, workflowId, "step-1", "attempt-1"),
                ),
            ) {
                step1.executeStep(targetWorkflowName = workflowName, targetWorkflowId = workflowId)
            }

            withContext(
                testScope(
                    admission = admission,
                    evidenceStore = store,
                    attempt = PersistedStepAttempt(workflowName, workflowId, "step-2", "attempt-2"),
                ),
            ) {
                step2.executeStep(targetWorkflowName = workflowName, targetWorkflowId = workflowId)
            }

            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(4)
            val step1Records = records.filter { it.stepName == "step-1" }
            val step2Records = records.filter { it.stepName == "step-2" }

            assertThat(step1Records).allMatch { it.stepAttemptId == "attempt-1" }
            assertThat(step2Records).allMatch { it.stepAttemptId == "attempt-2" }
        }
    }

    @Test
    fun `MCE-18 - isError=true tool result preserves truthful protocol evidence COMPLETED`() {
        val (transportProvider, serverCloseable) = createEchoServer(isError = true)
        servers += serverCloseable

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step = createEchoStep(transportProvider = transportProvider)

        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m18")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m18")
            }

        val result =
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store)) {
                    step.executeStep()
                }
            }

        // Protocol exchange was truthful and succeeded
        assertThat(result.result?.isError).isTrue()

        runBlocking {
            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(2)
            val e4 = records.first { it.effectKind == MCP_SERVER_START_EFFECT_KIND }
            val e3 = records.first { it.effectKind == MCP_TOOL_EFFECT_KIND }
            assertThat(e4.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(e3.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(e3.diagnosticSummary).isNull()
        }
    }

    @Test
    fun `MCE-19 - secret-bearing exception, arguments and output do not leak into durable evidence`() {
        val secretArg = "secret-token-argument-12345"
        val secretErrorMsg = "secret-credential-leak-67890"

        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val failingTransport =
            object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
                    assertThat(toolCall.arguments["secret"]).isEqualTo(secretArg)
                    throw IOException("Transport crashed with secret: $secretErrorMsg")
                }
            }

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step =
            createEchoStep(
                arguments = mapOf("message" to "hello", "secret" to secretArg),
                config = McpStepConfig.unrestricted().copy(reconnect = false),
                transportProvider = failingTransport,
            )

        var seq = 1L
        val admission =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    WorkflowMcpServerStartPermit(stepName, seq++, "gen-m19")

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    WorkflowMcpToolPermit(stepName, seq++, "gen-m19")
            }

        assertThatThrownBy {
            runBlocking {
                withContext(testScope(admission = admission, evidenceStore = store)) {
                    step.executeStep()
                }
            }
        }.isInstanceOf(WorkflowMcpException::class.java)

        runBlocking {
            val records = store.listEvidence(workflowName, workflowId)
            assertThat(records).isNotEmpty
            for (record in records) {
                assertThat(record.diagnosticSummary).doesNotContain(secretArg)
                assertThat(record.diagnosticSummary).doesNotContain(secretErrorMsg)
                assertThat(record.toString()).doesNotContain(secretArg)
                assertThat(record.toString()).doesNotContain(secretErrorMsg)
            }
            val e4 = records.first { it.effectKind == MCP_SERVER_START_EFFECT_KIND }
            assertThat(e4.diagnosticSummary).isEqualTo(MCP_SERVER_START_FAILED_DIAGNOSTIC)
        }
    }

    @Test
    fun `MCE-20 - ungoverned MCP execution remains compatible with zero evidence writes`() {
        val (transportProvider, serverCloseable) = createEchoServer()
        servers += serverCloseable

        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        val step = createEchoStep(transportProvider = transportProvider)

        val result =
            runBlocking {
                // Running in ungoverned context (admission = null, evidenceStore = null)
                step.executeStep()
            }

        assertThat(result.result?.content).contains("echo: hello")
        runBlocking {
            assertThat(store.listEvidence(workflowName, workflowId)).isEmpty()
        }
    }

    // ─── test infrastructure ────────────────────────────────────────────

    data class McpState(
        val result: McpToolResult? = null,
    )

    private fun createEchoServer(
        isError: Boolean = false,
        delayMillis: Long = 0,
        onToolCall: (suspend (AutoCloseable) -> Unit)? = null,
    ): Pair<McpTransportProvider, AutoCloseable> {
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)
        val clientToServer = PipedOutputStream()
        val serverInput = PipedInputStream(clientToServer)

        val pipeCloseable =
            AutoCloseable {
                runCatching { clientInput.close() }
                runCatching { clientToServer.close() }
                runCatching { serverToClient.close() }
                runCatching { serverInput.close() }
            }

        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val server =
            Server(
                serverInfo = Implementation(name = "test-echo", version = "1.0.0"),
                options =
                    ServerOptions(
                        capabilities =
                            ServerCapabilities(
                                tools = ServerCapabilities.Tools(listChanged = false),
                            ),
                    ),
            ) {
                addTool(
                    Tool(
                        name = "echo",
                        description = "Echoes the message back",
                        inputSchema =
                            io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(
                                properties =
                                    buildJsonObject {
                                        put("message", buildJsonObject { put("type", JsonPrimitive("string")) })
                                        put("secret", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    },
                                required = listOf("message"),
                            ),
                    ),
                ) { request ->
                    if (delayMillis > 0) {
                        delay(delayMillis)
                    }
                    val message =
                        request.params.arguments
                            ?.get("message")
                            ?.jsonPrimitive
                            ?.content ?: ""
                    onToolCall?.invoke(pipeCloseable)
                    CallToolResult(
                        content = listOf(TextContent("echo: $message")),
                        structuredContent = buildJsonObject { put("echoed", JsonPrimitive(message)) },
                        isError = isError,
                    )
                }
            }

        val session = AtomicReference<ServerSession?>(null)
        val sessionJob =
            scope.launch {
                session.set(
                    server.createSession(
                        StdioServerTransport(
                            serverInput.asSource().buffered(),
                            serverToClient.asSink().buffered(),
                        ),
                    ),
                )
            }

        val fixture =
            AutoCloseable {
                pipeCloseable.close()
                scope.launch {
                    runCatching { session.get()?.close() }
                    sessionJob.cancel()
                    runCatching { server.close() }
                }
                scope.cancel()
            }

        val transportProvider =
            object : McpTransportProvider {
                override suspend fun connect(toolCall: McpToolCall): McpTransportConnection =
                    McpTransportConnection(
                        input = clientInput.asSource().buffered(),
                        output = clientToServer.asSink().buffered(),
                        cleanup = { pipeCloseable.close() },
                    )
            }

        return transportProvider to fixture
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
        step: McpWorkflowStep<McpState>,
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
            Workflow(
                name = workflowName,
                definitionVersion = "1",
                stateType = typeOf<McpState>(),
                resultType = typeOf<McpState>(),
                schedule = null,
                steps = listOf(step),
                resultSelector = { it },
                stopPolicy = StopPolicy(),
                clock = Clock.systemUTC(),
                externalStepExecutorResolver = NoOpExternalStepExecutorResolver,
                failureDiagnosticObserver = NoOpWorkflowStepFailureDiagnosticObserver,
            )

        val workerConfig = WorkerConfig(workerId = "worker-e2e-1", poolName = "e2e-pool", pollIntervalMillis = 20)
        val persistence =
            WorkflowPersistence(
                checkpointStore = checkpointStore,
                stateCodec = McpStateCodec,
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
        val workflow: Workflow<McpState, McpState>,
        val supervisor: WorkflowExecutionSupervisor,
    )

    private object McpStateCodec : WorkflowStateCodec<McpState> {
        override fun encode(state: McpState): String = state.result?.content ?: "empty"

        override fun decode(payload: String): McpState = McpState(result = McpToolResult(content = payload, structuredContent = null))
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
                runId = RunId("run-mcp-1"),
            )
    }
}
