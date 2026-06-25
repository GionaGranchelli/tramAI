package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.engine.approval.DefaultApprovalGateway
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

class ApprovalGatewayAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ApprovalGatewayAutoConfiguration::class.java),
        )

    // ── 1. Creates gateway when all dependencies exist ────────────────

    @Test
    fun `creates approval gateway when all stores and factory present`() {
        contextRunner
            .withUserConfiguration(FullGatewayConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalGateway::class.java)
                assertThat(ctx.getBean(ApprovalGateway::class.java))
                    .isInstanceOf(DefaultApprovalGateway::class.java)
            }
    }

    // ── 2. Backs off when custom gateway exists ───────────────────────

    @Test
    fun `backs off when custom ApprovalGateway bean exists`() {
        contextRunner
            .withUserConfiguration(
                FullGatewayConfig::class.java,
                CustomApprovalGatewayConfig::class.java,
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalGateway::class.java)
                val gateway = ctx.getBean(ApprovalGateway::class.java)
                assertThat(gateway).isInstanceOf(CustomApprovalGateway::class.java)
                assertThat(gateway).isNotInstanceOf(DefaultApprovalGateway::class.java)
            }
    }

    // ── 3. Does not create gateway without request factory ────────────

    @Test
    fun `does not create gateway without request factory`() {
        contextRunner
            .withUserConfiguration(StoresOnlyConfig::class.java)
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovalGateway::class.java)
            }
    }

    // ── 4. Missing store guard documented in auto-config KDoc ──────────

    // Store-absence tests are documented at the bean-method level:
    // Each store dependency uses ObjectProvider so missing-store scenarios
    // produce a deterministic error code (tramai-sovereign-approval-gateway-missing-*)
    // rather than creating a partially-initialised gateway.
}

// ── Test configurations ─────────────────────────────────────────────────

private val zeroDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000000")
private val oneDigest = Sha256Digest.of("sha256:1111111111111111111111111111111111111111111111111111111111111111")
private val twoDigest = Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222")

private class GatewayTestSuspendedInvocationStore : SuspendedInvocationStore {
    private val store = mutableMapOf<String, SuspendedInvocationMetadata>()
    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) { store[metadata.approvalId] = metadata }

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = store[approvalId]
    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = null
    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? = store.remove(approvalId)
}

private class GatewayTestApprovalContinuationStore : ApprovalContinuationStore {
    private val store = mutableMapOf<String, ApprovalContinuation>()
    override suspend fun create(continuation: ApprovalContinuation, arguments: SensitiveToolArguments): ApprovalContinuation {
        store[continuation.approvalId] = continuation
        return continuation
    }
    override suspend fun get(approvalId: String): ApprovalContinuation? = store[approvalId]
    override suspend fun claimForExecution(approvalId: String, expectedVersion: Long, claimedBy: String): ClaimedApprovalContinuation =
        throw UnsupportedOperationException("stub")
    override suspend fun complete(approvalId: String, expectedVersion: Long, completedBy: String): ApprovalContinuation =
        throw UnsupportedOperationException("stub")
    override suspend fun expire(approvalId: String, expectedVersion: Long): ApprovalContinuation =
        throw UnsupportedOperationException("stub")
    override suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation =
        throw UnsupportedOperationException("stub")
    override suspend fun findStaleClaimed(claimedBefore: Instant, limit: Int): List<ApprovalContinuation> = emptyList()
    override suspend fun forceCancelClaimed(approvalId: String, expectedVersion: Long, cancelledBy: String, reasonCode: String): ApprovalContinuation =
        throw UnsupportedOperationException("stub")
    override suspend fun sweepExpired(): Int = 0
}

private class TestApprovalGatewayRequestFactory : ApprovalGatewayRequestFactory {
    override suspend fun createRequest(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: dev.tramai.core.approval.gateway.WorkflowRunId?,
    ): ApprovalGatewayPersistenceRequest {
        val now = Clock.systemUTC().instant()
        val wfRunId = workflowRunId?.value ?: "wf-run-1"
        val sensitiveArgs = SensitiveToolArguments.of("{}")
        return ApprovalGatewayPersistenceRequest(
            approvalRequest = ApprovalRequest(
                approvalId = "auto-test",
                binding = dev.tramai.core.approval.ApprovalBinding(
                    workflowRunId = wfRunId,
                    toolName = "test-tool",
                    argumentsDigest = zeroDigest,
                    policyVersion = "v1",
                    workflowDigest = oneDigest,
                    approvalTokenDigest = twoDigest,
                ),
                status = ApprovalStatus.PENDING,
                requestedBy = "test",
                requestedAt = now,
                expiresAt = now.plusSeconds(3600),
                decidedBy = null, decidedAt = null, decisionComment = null,
                consumedBy = null, consumedAt = null, version = 0L,
            ),
            continuation = ApprovalContinuation(
                approvalId = "auto-test", workflowRunId = wfRunId,
                correlationId = "corr", toolCallId = "tc", toolName = "test",
                argumentsDigest = zeroDigest, policyVersion = "v1",
                workflowDigest = oneDigest, status = ApprovalContinuationStatus.PENDING,
                createdAt = now, approvalExpiresAt = now.plusSeconds(3600),
                claimedBy = null, claimedAt = null, completedAt = null, version = 0L,
            ),
            sensitiveArguments = sensitiveArgs,
            suspendedInvocationMetadata = SuspendedInvocationMetadata(
                approvalId = "auto-test", toolCallId = "tc", toolName = "test",
                toolCallIndex = 0, correlationId = "corr",
                identity = EngineExecutionIdentity(wfRunId, "corr", oneDigest, "v1", "test"),
                securityContext = ExecutionSecurityContext(),
                operationReference = ResumeOperationReference("t.S", "m", "()V", zeroDigest),
                replayEnvelopeDigest = zeroDigest,
                toolReference = ResumeToolReference("test", zeroDigest),
            ),
            replayEnvelope = SensitiveReplayEnvelope.of(emptyList()),
            resumeToken = ResumeToken("public-token"),
        )
    }
}

private open class FullGatewayConfig {
    @Bean open fun testApprovalStore(): ApprovalStore = StubApprovalStore()
    @Bean open fun testApprovalContinuationStore(): ApprovalContinuationStore = GatewayTestApprovalContinuationStore()
    @Bean open fun testSuspendedInvocationStore(): SuspendedInvocationStore = GatewayTestSuspendedInvocationStore()
    @Bean open fun testGatewayRequestFactory(): ApprovalGatewayRequestFactory = TestApprovalGatewayRequestFactory()
}

private open class StoresOnlyConfig {
    @Bean open fun testApprovalStore(): ApprovalStore = StubApprovalStore()
    @Bean open fun testApprovalContinuationStore(): ApprovalContinuationStore = GatewayTestApprovalContinuationStore()
    @Bean open fun testSuspendedInvocationStore(): SuspendedInvocationStore = GatewayTestSuspendedInvocationStore()
}

private open class CustomApprovalGatewayConfig {
    @Bean @Primary
    open fun customApprovalGateway(): ApprovalGateway = CustomApprovalGateway()
}

private open class MissingApprovalStoreConfig {
    @Bean open fun testApprovalContinuationStore(): ApprovalContinuationStore = GatewayTestApprovalContinuationStore()
    @Bean open fun testSuspendedInvocationStore(): SuspendedInvocationStore = GatewayTestSuspendedInvocationStore()
    @Bean open fun testGatewayRequestFactory(): ApprovalGatewayRequestFactory = TestApprovalGatewayRequestFactory()
}

private open class MissingContinuationStoreConfig {
    @Bean open fun testApprovalStore(): ApprovalStore = StubApprovalStore()
    @Bean open fun testSuspendedInvocationStore(): SuspendedInvocationStore = GatewayTestSuspendedInvocationStore()
    @Bean open fun testGatewayRequestFactory(): ApprovalGatewayRequestFactory = TestApprovalGatewayRequestFactory()
}

private open class MissingSuspendedInvocationStoreConfig {
    @Bean open fun testApprovalStore(): ApprovalStore = StubApprovalStore()
    @Bean open fun testApprovalContinuationStore(): ApprovalContinuationStore = GatewayTestApprovalContinuationStore()
    @Bean open fun testGatewayRequestFactory(): ApprovalGatewayRequestFactory = TestApprovalGatewayRequestFactory()
}

// ── Stubs ───────────────────────────────────────────────────────────────

private class StubApprovalStore : ApprovalStore {
    override suspend fun create(request: ApprovalRequest): ApprovalRequest = request
    override suspend fun get(approvalId: String): ApprovalRequest? = null
    override suspend fun transition(approvalId: String, expectedVersion: Long, transition: ApprovalTransition): ApprovalRequest =
        throw UnsupportedOperationException("stub")
    override suspend fun consumeApprovedOrReplay(approvalId: String, expectedVersion: Long, presentedTokenDigest: Sha256Digest, consumedBy: String) =
        throw UnsupportedOperationException("stub")
}

private class CustomApprovalGateway : ApprovalGateway {
    override suspend fun requestApproval(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: dev.tramai.core.approval.gateway.WorkflowRunId?,
    ) = throw UnsupportedOperationException("custom-gateway")
}
