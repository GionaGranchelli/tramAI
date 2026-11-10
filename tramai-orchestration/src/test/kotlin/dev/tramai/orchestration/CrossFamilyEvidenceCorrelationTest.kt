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
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Phase 3 — Cross-Family Evidence Correlation Qualification:
 * Exercises a governed reference workflow spanning all supported effect families:
 * HTTP (E1), MCP (E3), Process (E7), and Approval Resume (E9), followed by
 * authoritative cancellation resulting in CANCELLED_UNCERTAIN.
 *
 * Proves:
 * 1. Run/generation correlation across all evidence families
 * 2. Monotonic admission sequence progression without collisions
 * 3. Distinct, typed parent provenance preservation
 * 4. Completed evidence retention after cancellation
 * 5. Zero secret leakage across stored records
 * 6. Historical timeline reconstruction and reconciliation analysis
 */
class CrossFamilyEvidenceCorrelationTest {
    private val workflowName = "governed-multi-effect-wf"
    private val workflowId = "run-corr-1"
    private val clock = Clock.systemUTC()
    private val digester = Sha256ToolArgumentsDigester()

    private val deploymentIdentity =
        WorkloadDeploymentIdentity(
            environmentId = EnvironmentId("prod-env"),
            workloadId = WorkloadId("governed-service"),
            deploymentId = DeploymentId("dep-corr-1"),
            configuration =
                WorkloadConfigurationIdentity(
                    id = ConfigurationId("cfg-prod"),
                    version = ConfigurationVersion("v2.1"),
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
        pool = JdbcConnectionPool.create("jdbc:h2:mem:d7_f_corr_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
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

    private fun initialCheckpoint() =
        WorkflowCheckpoint(
            workflowName = workflowName,
            workflowId = workflowId,
            nextStepIndex = 0,
            stepExecutions = 0,
            lastCompletedStepName = null,
            statePayload = "init",
            revision = 1L,
            checkpointGeneration = "gen-corr-1",
            metadata = encodeGovernedRunAttribution(governedIdentity),
        )

    @Test
    fun `cross-family correlation spans HTTP, MCP, Process, and E9 approval resumes with monotonic sequencing and complete retention`() {
        runBlocking {
            val canarySecret = "SUPER_SECRET_TOKEN_DO_NOT_LEAK_XYZ_999"
            val continuationStore = InMemoryApprovalContinuationStore()

            // 1. Initialize checkpoint row
            val savedCheckpoint = checkpointStore.save(initialCheckpoint())
            val generation = savedCheckpoint.checkpointGeneration ?: "gen-corr-1"

            // 2. Step 1: HTTP Effect (E1)
            val httpAdmission =
                checkpointStore.admitEffect(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    currentRevision = savedCheckpoint.revision,
                    currentGeneration = generation,
                    stepName = "http-fetch",
                    effectKind = HTTP_EFFECT_KIND,
                )
            assertThat(httpAdmission.sequence).isEqualTo(1L)
            val httpRecord =
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, generation, httpAdmission.sequence),
                    parent = ParentProvenance.StepAttempt("http-fetch", "att-http-1"),
                    effectKind = HTTP_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1000L,
                    completedAtEpochMillis = 1100L,
                )
            evidenceStore.recordEvidence(httpRecord)

            // 3. Step 2a: MCP Tool Effect (E3)
            val mcpAdmission =
                checkpointStore.admitEffect(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    currentRevision = savedCheckpoint.revision,
                    currentGeneration = generation,
                    stepName = "mcp-tool-call",
                    effectKind = "mcp_tool",
                )
            assertThat(mcpAdmission.sequence).isEqualTo(2L)
            val mcpRecord =
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, generation, mcpAdmission.sequence),
                    parent = ParentProvenance.StepAttempt("mcp-tool-call", "att-mcp-1"),
                    effectKind = "mcp_tool",
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1200L,
                    completedAtEpochMillis = 1300L,
                )
            evidenceStore.recordEvidence(mcpRecord)

            // 4. Step 2b: Process Effect (E7)
            val procAdmission =
                checkpointStore.admitEffect(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    currentRevision = savedCheckpoint.revision,
                    currentGeneration = generation,
                    stepName = "subshell-exec",
                    effectKind = PROCESS_EFFECT_KIND,
                )
            assertThat(procAdmission.sequence).isEqualTo(3L)
            val procRecord =
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey(workflowName, workflowId, generation, procAdmission.sequence),
                    parent = ParentProvenance.StepAttempt("subshell-exec", "att-proc-1"),
                    effectKind = PROCESS_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    createdAtEpochMillis = 1400L,
                    completedAtEpochMillis = 1500L,
                )
            evidenceStore.recordEvidence(procRecord)

            // 5. Step 3: Approval Continuation (E9) with sensitive arguments containing canary secret
            val sensitiveArgs = SensitiveToolArguments.of("""{"api_token":"$canarySecret","action":"transfer"}""")
            val continuation =
                ApprovalContinuation(
                    approvalId = "app-corr-1",
                    workflowRunId = workflowId,
                    correlationId = "corr-app-1",
                    toolCallId = "call-app-1",
                    toolName = "secure_transfer",
                    argumentsDigest = digester.digest(sensitiveArgs),
                    policyVersion = "policy-v1",
                    workflowDigest =
                        dev.tramai.core.approval.Sha256Digest
                            .of("sha256:" + "a".repeat(64)),
                    status = ApprovalContinuationStatus.PENDING,
                    createdAt = Instant.now(),
                    approvalExpiresAt = Instant.now().plusSeconds(600),
                    claimedBy = null,
                    claimedAt = null,
                    completedAt = null,
                    version = 0L,
                )
            continuationStore.create(continuation, sensitiveArgs)
            val claimed = continuationStore.claimForExecution("app-corr-1", 0L, "worker-1")

            val admissionAuthority =
                WorkflowApprovalResumeAdmission(
                    checkpointStore = checkpointStore,
                    continuationStore = continuationStore,
                    evidenceStore = evidenceStore,
                    approvalResumeEvidenceEnabled = true,
                    clock = clock,
                )

            val permit =
                admissionAuthority.admitResume(
                    ApprovalResumeAdmissionRequest(
                        approvalId = "app-corr-1",
                        toolName = "secure_transfer",
                        runIdentity = governedIdentity,
                        checkpointBinding = WorkflowCheckpointBinding(workflowName, workflowId),
                        continuationVersion = claimed.continuation.version,
                    ),
                )
            assertThat(permit.admissionSequence).isEqualTo(4L)
            assertThat(permit.evidenceHandle).isNotNull

            // Record execution dispatch and completion through the narrow E9 handle
            permit.evidenceHandle?.recordDispatched()
            permit.evidenceHandle?.recordCompleted()

            // Complete continuation in continuationStore
            continuationStore.complete("app-corr-1", claimed.continuation.version, "worker-1")

            // 6. Step 4: Authoritative Cancellation commits
            val cancelResult =
                checkpointStore.cancel(
                    WorkflowCancellationRequest(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        expectedRevision = savedCheckpoint.revision,
                        expectedGeneration = generation,
                        actor = "sec-ops",
                        reason = "post-execution audit cancellation",
                        cancelledAtEpochMillis = clock.millis(),
                    ),
                )

            // Assert conservative cancellation status
            assertThat(cancelResult.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED_UNCERTAIN)

            // 7. Verify All Evidence Records
            val records = evidenceStore.listEvidence(workflowName, workflowId)
            assertThat(records).hasSize(4)

            // Monotonic sequence 1..4
            assertThat(records.map { it.key.admissionSeq }).containsExactly(1L, 2L, 3L, 4L)

            // Generation and run correlation
            records.forEach { record ->
                assertThat(record.key.workflowName).isEqualTo(workflowName)
                assertThat(record.key.workflowId).isEqualTo(workflowId)
                assertThat(record.key.checkpointGeneration).isEqualTo(generation)
                assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            }

            // Distinct typed parent provenance
            val e9Record = records.first { it.key.admissionSeq == 4L }
            assertThat(e9Record.parent).isInstanceOf(ParentProvenance.ApprovalContinuation::class.java)
            val e9Parent = e9Record.parent as ParentProvenance.ApprovalContinuation
            assertThat(e9Parent.approvalId).isEqualTo("app-corr-1")
            assertThat(e9Parent.continuationVersion).isEqualTo(1L)
            assertThat(e9Parent.toolName).isEqualTo("secure_transfer")

            // Zero secret leakage in all evidence records
            records.forEach { record ->
                assertThat(record.toString()).doesNotContain(canarySecret)
            }

            // 8. Historical Timeline Analysis
            val timeline =
                WorkflowHistoricalEvidenceReader.readTimeline(
                    checkpointStore = checkpointStore,
                    evidenceStore = evidenceStore,
                    workflowName = workflowName,
                    workflowId = workflowId,
                    continuationStore = continuationStore,
                )

            assertThat(timeline.isCancelled).isTrue()
            assertThat(timeline.isUncertainCancellation).isTrue()
            assertThat(timeline.admissionGaps).isEmpty()
            assertThat(timeline.requiresReconciliation).isFalse()
            assertThat(timeline.records).hasSize(4)
        }
    }
}
