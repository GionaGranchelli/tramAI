package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import kotlin.reflect.full.memberProperties

class SovereignOpsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SovereignOpsAutoConfiguration::class.java),
        )

    // ── Happy path: beans exist ─────────────────────────────────────────

    @Test
    fun `creates approval operations bean`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignApprovalOperations::class.java)
            }
    }

    @Test
    fun `creates suspended invocation operations bean`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(
                    SovereignSuspendedInvocationOperations::class.java,
                )
            }
    }

    @Test
    fun `creates audit operations bean`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignAuditOperations::class.java)
            }
    }

    @Test
    fun `creates runtime operations bean`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignRuntimeOperations::class.java)
            }
    }

    // ── P1: Startup safety when ApprovalStore is absent ────────────────

    @Test
    fun `does not fail startup when approval store is absent`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfigWithoutApproval::class.java,
            )
            .run { ctx ->
                // Approval operations bean should not be created
                assertThat(ctx).doesNotHaveBean(
                    SovereignApprovalOperations::class.java,
                )
                // Other ops beans should still exist
                assertThat(ctx).hasSingleBean(
                    SovereignAuditOperations::class.java,
                )
                assertThat(ctx).hasSingleBean(
                    SovereignRuntimeOperations::class.java,
                )
            }
    }

    // ── enabled=false disables ops beans ────────────────────────────────

    @Test
    fun `ops disabled when enabled is false`() {
        contextRunner
            .withPropertyValues("tramai.sovereign.ops.enabled=false")
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(SovereignApprovalOperations::class.java)
                assertThat(ctx).doesNotHaveBean(
                    SovereignSuspendedInvocationOperations::class.java,
                )
                assertThat(ctx).doesNotHaveBean(SovereignAuditOperations::class.java)
                assertThat(ctx).doesNotHaveBean(SovereignRuntimeOperations::class.java)
            }
    }

    // ── mutationsEnabled=false blocks deny ─────────────────────────────

    @Test
    fun `mutations disabled blocks deny approval`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("test-approval", "actor", "reason")
                    }
                }.exceptionOrNull()
                assertThat(ex)
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("tramai-sovereign-ops-mutations-disabled")
            }
    }

    // ── ID validation ──────────────────────────────────────────────────

    @Test
    fun `blank approval id is rejected`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val ex = runCatching {
                    runBlocking { ops.getApproval("") }
                }.exceptionOrNull()
                assertThat(ex)
                    .hasMessageContaining("tramai-sovereign-ops-invalid-approval-id")
            }
    }

    @Test
    fun `invalid actor is rejected with correct error code`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("test-approval", " ", "reason")
                    }
                }.exceptionOrNull()
                assertThat(ex)
                    .hasMessageContaining("tramai-sovereign-ops-invalid-actor")
            }
    }

    @Test
    fun `blank reason is rejected with correct error code`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("test-approval", "admin", "")
                    }
                }.exceptionOrNull()
                assertThat(ex)
                    .hasMessageContaining("tramai-sovereign-ops-invalid-reason")
            }
    }

    @Test
    fun `blank suspended invocation id is rejected`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(
                    SovereignSuspendedInvocationOperations::class.java,
                )
                val ex = runCatching {
                    runBlocking { ops.getSuspendedInvocation("") }
                }.exceptionOrNull()
                assertThat(ex)
                    .hasMessageContaining(
                        "tramai-sovereign-ops-invalid-suspended-invocation-id",
                    )
            }
    }

    @Test
    fun `blank audit stream id is rejected`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(SovereignAuditOperations::class.java)
                val ex = runCatching {
                    runBlocking { ops.readAuditStream("") }
                }.exceptionOrNull()
                assertThat(ex)
                    .hasMessageContaining("tramai-sovereign-ops-invalid-audit-stream-id")
            }
    }

    @Test
    fun `audit stream id over 128 chars is rejected`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(SovereignAuditOperations::class.java)
                val longId = "a".repeat(129)
                val ex = runCatching {
                    runBlocking { ops.readAuditStream(longId) }
                }.exceptionOrNull()
                assertThat(ex)
                    .hasMessageContaining("tramai-sovereign-ops-invalid-audit-stream-id")
            }
    }

    // ── Page size enforcement ──────────────────────────────────────────

    @Test
    fun `page size beyond max is rejected`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.max-page-size=10")
            .run { ctx ->
                val ops = ctx.getBean(SovereignAuditOperations::class.java)
                val ex = runCatching {
                    runBlocking { ops.readAuditStream("test-stream", limit = 50) }
                }.exceptionOrNull()
                assertThat(ex)
                    .hasMessageContaining("tramai-sovereign-ops-page-size-too-large")
            }
    }

    // ── Custom bean is not overridden ──────────────────────────────────

    @Test
    fun `custom approval operations bean is not overridden`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                CustomApprovalOpsConfig::class.java,
            )
            .run { ctx ->
                assertThat(ctx.getBeansOfType(SovereignApprovalOperations::class.java))
                    .hasSize(1)
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                assertThat(ops).isInstanceOf(CustomApprovalOperations::class.java)
            }
    }

    @Test
    fun `custom audit operations bean is not overridden`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                CustomAuditOpsConfig::class.java,
            )
            .run { ctx ->
                assertThat(ctx.getBeansOfType(SovereignAuditOperations::class.java))
                    .hasSize(1)
                val ops = ctx.getBean(SovereignAuditOperations::class.java)
                assertThat(ops).isInstanceOf(CustomAuditOperations::class.java)
            }
    }

    // ── Approval summary does not expose tokens ────────────────────────

    @Test
    fun `approval summary does not expose approval token`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val summary = runBlocking {
                    ops.getApproval("test-approval")
                }
                assertThat(summary).isNotNull
                val props = SovereignApprovalSummary::class.memberProperties
                    .map { it.name }
                assertThat(props).doesNotContain("approvalToken")
                assertThat(props).doesNotContain("approvalTokenDigest")
                assertThat(props).doesNotContain("resumeToken")
                assertThat(props).doesNotContain("token")
            }
    }

    @Test
    fun `suspended summary does not expose replay envelope`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(
                    SovereignSuspendedInvocationOperations::class.java,
                )
                val summary = runBlocking {
                    ops.getSuspendedInvocation("test-suspended")
                }
                assertThat(summary).isNotNull
                val props = SovereignSuspendedInvocationSummary::class.memberProperties
                    .map { it.name }
                assertThat(props).doesNotContain("replayEnvelope")
                assertThat(props).doesNotContain("rawEnvelope")
                assertThat(props).doesNotContain("arguments")
                assertThat(props).doesNotContain("toolArguments")
                assertThat(props).doesNotContain("createdAt")
            }
    }

    // ── Audit event summary does not map raw payload ────────────────────

    @Test
    fun `audit events are mapped to safe summaries`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(SovereignAuditOperations::class.java)
                val events = runBlocking {
                    ops.readAuditStream("test-stream")
                }
                assertThat(events).isNotEmpty
                val props = SovereignAuditEventSummary::class.memberProperties
                    .map { it.name }
                assertThat(props).doesNotContain("metadata")
                assertThat(props).doesNotContain("prompt")
                assertThat(props).doesNotContain("response")
                assertThat(props).doesNotContain("rawContent")
                assertThat(props).doesNotContain("documentContent")
            }
    }

    // ── Runtime status ─────────────────────────────────────────────────

    @Test
    fun `runtime status detects memory backed stores`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(SovereignRuntimeOperations::class.java)
                val status = ops.status()
                assertThat(status.runtimeAvailable).isTrue
                assertThat(status.auditStoreAvailable).isTrue
                assertThat(status.approvalStoreAvailable).isTrue
                assertThat(status.approvalContinuationStoreAvailable).isTrue
                assertThat(status.suspendedInvocationStoreAvailable).isTrue
                assertThat(status.persistenceMode).isEqualTo("memory")
            }
    }
}

// ── Test services ─────────────────────────────────────────────────────

class TestApprovalContinuationStore :
    dev.tramai.core.approval.ApprovalContinuationStore {
    override suspend fun create(
        continuation: dev.tramai.core.approval.ApprovalContinuation,
        arguments: dev.tramai.core.approval.SensitiveToolArguments,
    ): dev.tramai.core.approval.ApprovalContinuation = continuation

    override suspend fun get(
        approvalId: String,
    ): dev.tramai.core.approval.ApprovalContinuation? = null

    override suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): dev.tramai.core.approval.ClaimedApprovalContinuation =
        throw UnsupportedOperationException("stub")

    override suspend fun complete(
        approvalId: String,
        expectedVersion: Long,
        completedBy: String,
    ): dev.tramai.core.approval.ApprovalContinuation =
        throw UnsupportedOperationException("stub")

    override suspend fun expire(
        approvalId: String,
        expectedVersion: Long,
    ): dev.tramai.core.approval.ApprovalContinuation =
        throw UnsupportedOperationException("stub")

    override suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ): dev.tramai.core.approval.ApprovalContinuation =
        throw UnsupportedOperationException("stub")

    override suspend fun findStaleClaimed(
        claimedBefore: Instant,
        limit: Int,
    ): List<dev.tramai.core.approval.ApprovalContinuation> = emptyList()

    override suspend fun forceCancelClaimed(
        approvalId: String,
        expectedVersion: Long,
        cancelledBy: String,
        reasonCode: String,
    ): dev.tramai.core.approval.ApprovalContinuation =
        throw UnsupportedOperationException("stub")

    override suspend fun sweepExpired(): Int = 0
}

class CustomApprovalOperations : SovereignApprovalOperations {
    override suspend fun getApproval(
        approvalId: String,
    ): SovereignApprovalSummary? = null
    override suspend fun denyApproval(
        approvalId: String,
        actor: String,
        reason: String,
    ): SovereignApprovalSummary =
        throw UnsupportedOperationException("custom stub")
}

class CustomAuditOperations : SovereignAuditOperations {
    override suspend fun readAuditStream(
        auditStreamId: String,
        limit: Int,
    ): List<SovereignAuditEventSummary> = emptyList()
    override suspend fun latestAuditEvent(
        auditStreamId: String,
    ): SovereignAuditEventSummary? = null
}

// ── Test configuration classes ────────────────────────────────────────

open class MinimalStoreConfig {
    @Bean open fun testAuditStore(): AuditStore = TestAuditStore()

    @Bean open fun testApprovalStore(): ApprovalStore = TestApprovalStore()

    @Bean open fun testApprovalContinuationStore():
        dev.tramai.core.approval.ApprovalContinuationStore =
        TestApprovalContinuationStore()

    @Bean open fun testSuspendedInvocationStore(): SuspendedInvocationStore =
        TestSuspendedInvocationStore()
}

open class MinimalStoreConfigWithoutApproval {
    @Bean open fun testAuditStore(): AuditStore = TestAuditStore()

    @Bean open fun testSuspendedInvocationStore(): SuspendedInvocationStore =
        TestSuspendedInvocationStore()
}

open class CustomApprovalOpsConfig {
    @Bean @Primary
    open fun customApprovalOps(): SovereignApprovalOperations =
        CustomApprovalOperations()
}

open class CustomAuditOpsConfig {
    @Bean @Primary
    open fun customAuditOps(): SovereignAuditOperations =
        CustomAuditOperations()
}

// ── Test store stubs ──────────────────────────────────────────────────

class TestApprovalStore : ApprovalStore {
    private val store = mutableMapOf<String, ApprovalRequest>()

    init {
        store["test-approval"] = ApprovalRequest(
            approvalId = "test-approval",
            binding = dev.tramai.core.approval.ApprovalBinding(
                workflowRunId = "wf-1",
                toolName = "test-tool",
                argumentsDigest = dev.tramai.core.approval.Sha256Digest.of(
                    "sha256:${"a".repeat(64)}",
                ),
                policyVersion = "1.0",
                workflowDigest = dev.tramai.core.approval.Sha256Digest.of(
                    "sha256:${"b".repeat(64)}",
                ),
                approvalTokenDigest = dev.tramai.core.approval.Sha256Digest.of(
                    "sha256:${"c".repeat(64)}",
                ),
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = "requester",
            requestedAt = Instant.parse("2026-06-01T00:00:00Z"),
            expiresAt = Instant.parse("2026-06-01T00:15:00Z"),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0,
        )
    }

    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        store[request.approvalId] = request
        return request
    }

    override suspend fun get(approvalId: String): ApprovalRequest? =
        store[approvalId]

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest {
        val current = store[approvalId]
            ?: throw IllegalArgumentException("not found")
        val updated = current.copy(
            status = ApprovalStatus.DENIED,
            decidedBy = when (transition) {
                is ApprovalTransition.Deny -> transition.decidedBy
                else -> null
            },
            decisionComment = when (transition) {
                is ApprovalTransition.Deny -> transition.comment
                else -> null
            },
            version = current.version + 1,
        )
        store[approvalId] = updated
        return updated
    }

    override suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: dev.tramai.core.approval.Sha256Digest,
        consumedBy: String,
    ) = throw UnsupportedOperationException("stub")
}

class TestAuditStore : AuditStore {
    private val streams = mutableMapOf<String, MutableList<AuditEvent>>()

    init {
        val event = AuditEvent(
            schemaVersion = 1,
            hashAlgorithm = dev.tramai.security.audit.AuditHashAlgorithm.SHA_256,
            auditStreamId = "test-stream",
            eventId = "evt-1",
            sequenceNumber = 1,
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            actor = "tester",
            enforcementPoint = "approval-gate",
            decision = "permit",
            policyVersion = null,
            workflowDigest = null,
            previousEventHash = null,
            eventHash = "a".repeat(64),
            timestamp = Instant.parse("2026-06-01T00:00:00Z"),
            reasonCode = null,
        )
        streams["test-stream"] = mutableListOf(event)
    }

    override suspend fun appendNext(
        auditStreamId: String,
        eventFactory: (AuditEvent?) -> AuditEvent,
    ): AuditEvent {
        val latest = streams[auditStreamId]?.lastOrNull()
        val event = eventFactory(latest)
        streams.getOrPut(auditStreamId) { mutableListOf() }.add(event)
        return event
    }

    override suspend fun readStream(auditStreamId: String): List<AuditEvent> =
        streams[auditStreamId] ?: emptyList()

    override suspend fun latestEvent(auditStreamId: String): AuditEvent? =
        streams[auditStreamId]?.lastOrNull()
}

class TestSuspendedInvocationStore : SuspendedInvocationStore {
    private val store = mutableMapOf<String, dev.tramai.engine.SuspendedInvocationMetadata>()

    init {
        val metadata = dev.tramai.engine.SuspendedInvocationMetadata(
            approvalId = "test-suspended",
            toolCallId = "tc-1",
            toolName = "test-tool",
            toolCallIndex = 0,
            correlationId = "corr-suspended",
            identity = dev.tramai.engine.EngineExecutionIdentity(
                workflowRunId = "wf-suspended",
                correlationId = "corr-suspended",
                workflowDigest = dev.tramai.core.approval.Sha256Digest.of(
                    "sha256:${"a".repeat(64)}",
                ),
                policyVersion = "1.0",
                actorId = "system",
            ),
            securityContext = dev.tramai.engine.ExecutionSecurityContext(
                dataClassification = null,
                classificationSource = null,
            ),
            operationReference = dev.tramai.engine.ResumeOperationReference(
                serviceInterface = "com.example.TestService",
                methodName = "process",
                jvmMethodDescriptor = "(Ljava/lang/String;)V",
                resumeDefinitionDigest = dev.tramai.core.approval.Sha256Digest.of(
                    "sha256:${"d".repeat(64)}",
                ),
            ),
            replayEnvelopeDigest = dev.tramai.core.approval.Sha256Digest.of(
                "sha256:${"e".repeat(64)}",
            ),
            conversationId = null,
            historySize = 5,
            tokenBudgetSnapshot = null,
            toolReference = dev.tramai.engine.ResumeToolReference(
                toolName = "test-tool",
                declarationDigest = dev.tramai.core.approval.Sha256Digest.of(
                    "sha256:${"f".repeat(64)}",
                ),
            ),
            toolSecurity = null,
        )
        store["test-suspended"] = metadata
    }

    override suspend fun create(
        metadata: dev.tramai.engine.SuspendedInvocationMetadata,
        replayEnvelope: dev.tramai.engine.SensitiveReplayEnvelope,
    ) {
        store[metadata.approvalId] = metadata
    }

    override suspend fun get(
        approvalId: String,
    ): dev.tramai.engine.SuspendedInvocationMetadata? = store[approvalId]

    override suspend fun revealReplayEnvelope(
        approvalId: String,
    ): dev.tramai.engine.SensitiveReplayEnvelope? = null

    override suspend fun remove(
        approvalId: String,
    ): dev.tramai.engine.SuspendedInvocationMetadata? = store.remove(approvalId)
}
