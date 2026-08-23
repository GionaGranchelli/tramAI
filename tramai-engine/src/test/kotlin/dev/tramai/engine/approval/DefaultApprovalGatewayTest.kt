package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.AuditStreamId
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.engine.ReplayEnvelopeDigestHelper
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.inMemorySuspendedInvocationStore
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefaultApprovalGatewayTest {

    private val fixedClock: Clock = Clock.fixed(
        Instant.parse("2026-06-25T10:00:00Z"),
        ZoneId.of("UTC"),
    )

    private lateinit var approvalStore: InMemoryApprovalStore
    private lateinit var continuationStore: InMemoryApprovalContinuationStore
    private lateinit var suspendedInvocationStore: SuspendedInvocationStore
    private lateinit var factory: FakeApprovalGatewayRequestFactory

    private fun createGateway(): DefaultApprovalGateway = DefaultApprovalGateway(
        approvalStore = approvalStore,
        continuationStore = continuationStore,
        suspendedInvocationStore = suspendedInvocationStore,
        requestFactory = factory,
        clock = fixedClock,
    )

    @BeforeEach
    fun setUp() {
        approvalStore = InMemoryApprovalStore(
            clock = fixedClock,
            maxCreationTtl = Duration.ofHours(2),
        )
        continuationStore = InMemoryApprovalContinuationStore(
            clock = fixedClock,
            maxContinuationTtl = Duration.ofHours(2),
        )
        suspendedInvocationStore = inMemorySuspendedInvocationStore()
        factory = FakeApprovalGatewayRequestFactory(
            fixedClock = fixedClock,
            defaultApprovalId = "gateway-test-1",
        )
    }

    // -----------------------------------------------------------------------
    // 1. New request persists all three records and returns Suspended
    // -----------------------------------------------------------------------

    @Test
    fun `new request persists approval, suspended invocation, and continuation`(): Unit = runBlocking {
        val gateway = createGateway()
        val approvalId = "fresh-request"
        factory.defaultApprovalId = approvalId

        val result = gateway.requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Medical review required"),
            requiredRole = ApproverRole("medical-reviewer"),
        )

        val storedApproval = approvalStore.get(approvalId)
        assertThat(storedApproval).isNotNull
        assertThat(storedApproval!!.status).isEqualTo(ApprovalStatus.PENDING)

        val storedMetadata = suspendedInvocationStore.get(approvalId)
        assertThat(storedMetadata).isNotNull

        val storedContinuation = continuationStore.get(approvalId)
        assertThat(storedContinuation).isNotNull

        assertThat(result).isInstanceOf(ApprovalRequestResult.Suspended::class.java)
        val suspended = result as ApprovalRequestResult.Suspended
        assertThat(suspended.approvalId.value).isEqualTo(approvalId)
        assertThat(suspended.workflowRunId.value).isEqualTo("wf-run-1")
        assertThat(suspended.auditStreamId.value).isNotEmpty()
        assertThat(suspended.resumeToken.value).isNotEmpty()
    }

    // -----------------------------------------------------------------------
    // 2. Does not block waiting for human (returns Suspended immediately)
    // -----------------------------------------------------------------------

    @Test
    fun `new request returns immediately without blocking`(): Unit = runBlocking {
        val gateway = createGateway()

        val result = gateway.requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Quick review"),
            requiredRole = ApproverRole("reviewer"),
        )

        assertThat(result).isInstanceOf(ApprovalRequestResult.Suspended::class.java)
    }

    // -----------------------------------------------------------------------
    // 3. Existing pending — duplicate does not create, no duplicate store writes
    // -----------------------------------------------------------------------

    @Test
    fun `existing pending approval returns Suspended without duplicate create`(): Unit = runBlocking {
        val approvalId = "duplicate-pending"
        factory.defaultApprovalId = approvalId
        val gateway = createGateway()
        val publicToken = ResumeToken("public-pending-token")
        factory.defaultResumeToken = publicToken

        // First call creates the request
        val firstResult = gateway.requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Review"),
            requiredRole = ApproverRole("medical-reviewer"),
        )
        assertThat(firstResult).isInstanceOf(ApprovalRequestResult.Suspended::class.java)
        val firstSuspended = firstResult as ApprovalRequestResult.Suspended

        // Capture state after first call
        val initialApprovalVersion = approvalStore.get(approvalId)!!.version
        assertThat(suspendedInvocationStore.get(approvalId)).isNotNull()
        assertThat(continuationStore.get(approvalId)).isNotNull()

        // Second call with same data — should not duplicate store writes
        val secondResult = gateway.requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Review"),
            requiredRole = ApproverRole("medical-reviewer"),
        )

        assertThat(secondResult).isInstanceOf(ApprovalRequestResult.Suspended::class.java)
        val secondSuspended = secondResult as ApprovalRequestResult.Suspended
        assertThat(secondSuspended.approvalId).isEqualTo(firstSuspended.approvalId)

        // Approval version unchanged — create was not called again
        val approvalAfterSecondCall = approvalStore.get(approvalId)!!
        assertThat(approvalAfterSecondCall.version).isEqualTo(initialApprovalVersion)

        // Suspended invocation and continuation still exist and were not duplicated
        assertThat(suspendedInvocationStore.get(approvalId)).isNotNull()
        assertThat(continuationStore.get(approvalId)).isNotNull()

        // Resume token from factory, not from stored digest
        assertThat(secondSuspended.resumeToken).isEqualTo(publicToken)
        assertThat(secondSuspended.resumeToken.value).isNotEqualTo(twoDigest.value)
    }

    // -----------------------------------------------------------------------
    // 4. Existing approved request returns AlreadyApproved
    // -----------------------------------------------------------------------

    @Test
    fun `existing approved approval returns AlreadyApproved`(): Unit = runBlocking {
        val approvalId = "already-approved"
        val requestingActor = "test-actor"
        val decidingActor = "human-1"
        val factory = FakeApprovalGatewayRequestFactory(
            fixedClock = fixedClock,
            defaultApprovalId = approvalId,
            defaultRequestedBy = requestingActor,
        )
        val gateway = DefaultApprovalGateway(
            approvalStore = approvalStore,
            continuationStore = continuationStore,
            suspendedInvocationStore = suspendedInvocationStore,
            requestFactory = factory,
            clock = fixedClock,
        )

        val pendingRequest = createPendingApprovalRequest(approvalId, workflowRunId = "wf-approved", requestedBy = requestingActor)
        approvalStore.create(pendingRequest)
        approvalStore.transition(
            approvalId = approvalId,
            expectedVersion = 0L,
            transition = ApprovalTransition.Approve(decidedBy = decidingActor, comment = "Looks good"),
        )

        val result = gateway.requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Review"),
            requiredRole = ApproverRole("medical-reviewer"),
        )

        assertThat(result).isInstanceOf(ApprovalRequestResult.AlreadyApproved::class.java)
        val approved = result as ApprovalRequestResult.AlreadyApproved
        assertThat(approved.decision.decidedBy).isEqualTo(decidingActor)
        assertThat(approved.decision.comment).isEqualTo("Looks good")
    }

    // -----------------------------------------------------------------------
    // 5. Existing denied request returns AlreadyDenied
    // -----------------------------------------------------------------------

    @Test
    fun `existing denied approval returns AlreadyDenied`(): Unit = runBlocking {
        val approvalId = "already-denied"
        val requestingActor = "test-actor"
        val decidingActor = "human-2"
        val factory = FakeApprovalGatewayRequestFactory(
            fixedClock = fixedClock,
            defaultApprovalId = approvalId,
            defaultRequestedBy = requestingActor,
        )
        val gateway = DefaultApprovalGateway(
            approvalStore = approvalStore,
            continuationStore = continuationStore,
            suspendedInvocationStore = suspendedInvocationStore,
            requestFactory = factory,
            clock = fixedClock,
        )

        val pendingRequest = createPendingApprovalRequest(approvalId, workflowRunId = "wf-denied", requestedBy = requestingActor)
        approvalStore.create(pendingRequest)
        approvalStore.transition(
            approvalId = approvalId,
            expectedVersion = 0L,
            transition = ApprovalTransition.Deny(decidedBy = decidingActor, comment = "Not appropriate"),
        )

        val result = gateway.requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Review"),
            requiredRole = ApproverRole("medical-reviewer"),
        )

        assertThat(result).isInstanceOf(ApprovalRequestResult.AlreadyDenied::class.java)
        val denied = result as ApprovalRequestResult.AlreadyDenied
        assertThat(denied.decision.decidedBy).isEqualTo(decidingActor)
        assertThat(denied.decision.reason).isEqualTo("Not appropriate")
    }

    // -----------------------------------------------------------------------
    // 6. Existing expired/timed-out request returns Expired
    // -----------------------------------------------------------------------

    @Test
    fun `existing timed-out approval returns Expired`(): Unit = runBlocking {
        val approvalId = "already-timed-out"
        val requestingActor = "test-actor"
        val mutableClock = MutableTestClock(fixedClock.instant())
        val expiry = mutableClock.instant().plusSeconds(60)

        val timeoutingStore = InMemoryApprovalStore(
            clock = mutableClock,
            maxCreationTtl = Duration.ofHours(2),
        )
        val timeoutingContinuationStore = InMemoryApprovalContinuationStore(
            clock = mutableClock,
            maxContinuationTtl = Duration.ofHours(2),
        )
        val factory = FakeApprovalGatewayRequestFactory(
            fixedClock = mutableClock,
            defaultApprovalId = approvalId,
            defaultRequestedBy = requestingActor,
        )
        val gateway = DefaultApprovalGateway(
            approvalStore = timeoutingStore,
            continuationStore = timeoutingContinuationStore,
            suspendedInvocationStore = inMemorySuspendedInvocationStore(),
            requestFactory = factory,
            clock = mutableClock,
        )

        val pendingRequest = createPendingApprovalRequest(
            approvalId = approvalId,
            workflowRunId = "wf-expired",
            requestedBy = requestingActor,
            requestedAt = mutableClock.instant(),
            expiresAt = expiry,
        )
        timeoutingStore.create(pendingRequest)

        mutableClock.advance(Duration.ofMinutes(5))

        timeoutingStore.transition(
            approvalId = approvalId,
            expectedVersion = 0L,
            transition = ApprovalTransition.Timeout,
        )

        val result = gateway.requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Review"),
            requiredRole = ApproverRole("medical-reviewer"),
        )

        assertThat(result).isInstanceOf(ApprovalRequestResult.Expired::class.java)
        val expired = result as ApprovalRequestResult.Expired
        assertThat(expired.approvalId.value).isEqualTo(approvalId)
        assertThat(expired.reason).isEqualTo("approval-expired")
    }

    @Test
    fun `expired-by-clock approval returns Expired`(): Unit = runBlocking {
        val approvalId = "clock-expired"
        val requestingActor = "test-actor"
        val mutableClock = MutableTestClock(fixedClock.instant())
        val pastExpiry = mutableClock.instant().plusSeconds(60)

        val expiringStore = InMemoryApprovalStore(
            clock = mutableClock,
            maxCreationTtl = Duration.ofHours(2),
        )
        val expiringContinuationStore = InMemoryApprovalContinuationStore(
            clock = mutableClock,
            maxContinuationTtl = Duration.ofHours(2),
        )
        val factory = FakeApprovalGatewayRequestFactory(
            fixedClock = mutableClock,
            defaultApprovalId = approvalId,
            defaultRequestedBy = requestingActor,
        )
        val gateway = DefaultApprovalGateway(
            approvalStore = expiringStore,
            continuationStore = expiringContinuationStore,
            suspendedInvocationStore = inMemorySuspendedInvocationStore(),
            requestFactory = factory,
            clock = mutableClock,
        )

        val pendingRequest = createPendingApprovalRequest(
            approvalId = approvalId,
            workflowRunId = "wf-expired-clock",
            requestedBy = requestingActor,
            requestedAt = mutableClock.instant(),
            expiresAt = pastExpiry,
        )
        expiringStore.create(pendingRequest)

        mutableClock.advance(Duration.ofMinutes(5))

        val result = gateway.requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Review"),
            requiredRole = ApproverRole("medical-reviewer"),
        )

        assertThat(result).isInstanceOf(ApprovalRequestResult.Expired::class.java)
        val expired = result as ApprovalRequestResult.Expired
        assertThat(expired.approvalId.value).isEqualTo(approvalId)
        assertThat(expired.reason).isEqualTo("approval-expired")
    }

    // -----------------------------------------------------------------------
    // 7. CancellationException is rethrown
    // -----------------------------------------------------------------------

    @Test
    fun `cancellation exception from store is rethrown`(): Unit = runBlocking {
        val failingStore = object : ApprovalStore by approvalStore {
            override suspend fun create(request: ApprovalRequest): ApprovalRequest {
                throw CancellationException("simulated-cancel")
            }
        }
        val gateway = DefaultApprovalGateway(
            approvalStore = failingStore,
            continuationStore = continuationStore,
            suspendedInvocationStore = suspendedInvocationStore,
            requestFactory = factory,
            clock = fixedClock,
        )

        val thrown = try {
            gateway.requestApproval(
                subject = ApprovalSubject("claim-42"),
                recommendation = ApprovalRecommendation("review", "Cancel test"),
                requiredRole = ApproverRole("reviewer"),
            )
            null
        } catch (e: CancellationException) {
            e
        }

        assertThat(thrown).isNotNull
        assertThat(thrown!!.message).isEqualTo("simulated-cancel")
    }

    // -----------------------------------------------------------------------
    // 8. Factory failure fails closed without creating approval
    // -----------------------------------------------------------------------

    @Test
    fun `factory failure does not write stores`(): Unit = runBlocking {
        val failingFactory = object : ApprovalGatewayRequestFactory {
            override suspend fun createRequest(
                subject: ApprovalSubject,
                recommendation: ApprovalRecommendation,
                requiredRole: ApproverRole,
                workflowRunId: WorkflowRunId?,
            ): ApprovalGatewayPersistenceRequest {
                throw RuntimeException("factory-exploded")
            }
        }
        val gateway = DefaultApprovalGateway(
            approvalStore = approvalStore,
            continuationStore = continuationStore,
            suspendedInvocationStore = suspendedInvocationStore,
            requestFactory = failingFactory,
            clock = fixedClock,
        )

        val thrown = try {
            gateway.requestApproval(
                subject = ApprovalSubject("claim-42"),
                recommendation = ApprovalRecommendation("review", "Factory failure"),
                requiredRole = ApproverRole("reviewer"),
            )
            null
        } catch (e: RuntimeException) {
            e
        }

        assertThat(thrown).isNotNull
        assertThat(thrown!!.message).isEqualTo("factory-exploded")

        assertThat(approvalStore.get("any-id")).isNull()
        assertThat(continuationStore.get("any-id")).isNull()
        assertThat(suspendedInvocationStore.get("any-id")).isNull()
    }

    // -----------------------------------------------------------------------
    // 9. ResumeToken is the public credential, not the approval token digest
    // -----------------------------------------------------------------------

    @Test
    fun `suspended result uses public resume token from factory not approval token digest`(): Unit = runBlocking {
        val approvalId = "token-test"
        factory.defaultApprovalId = approvalId
        val publicToken = ResumeToken("public-resume-token")
        factory.defaultResumeToken = publicToken

        val result = createGateway().requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Review"),
            requiredRole = ApproverRole("medical-reviewer"),
        )

        val suspended = result as ApprovalRequestResult.Suspended
        assertThat(suspended.resumeToken).isEqualTo(publicToken)
        assertThat(suspended.resumeToken.value).isNotEqualTo(twoDigest.value)
    }

    @Test
    fun `existing pending result uses factory resume token not stored digest`(): Unit = runBlocking {
        val approvalId = "duplicate-token"
        factory.defaultApprovalId = approvalId
        val publicToken = ResumeToken("public-existing-pending-token")
        factory.defaultResumeToken = publicToken
        val gateway = createGateway()

        // First call creates
        gateway.requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Review"),
            requiredRole = ApproverRole("medical-reviewer"),
        )

        // Second call returns existing pending
        val result = gateway.requestApproval(
            subject = ApprovalSubject("claim-42"),
            recommendation = ApprovalRecommendation("review", "Review"),
            requiredRole = ApproverRole("medical-reviewer"),
        )

        val suspended = result as ApprovalRequestResult.Suspended
        assertThat(suspended.resumeToken).isEqualTo(publicToken)
        assertThat(suspended.resumeToken.value).isNotEqualTo(twoDigest.value)
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private val zeroDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000000")
    private val oneDigest = Sha256Digest.of("sha256:1111111111111111111111111111111111111111111111111111111111111111")
    internal val twoDigest = Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222")

    private fun createPendingApprovalRequest(
        approvalId: String,
        workflowRunId: String = "wf-run-${approvalId}",
        requestedBy: String = "test-actor",
        requestedAt: Instant = fixedClock.instant(),
        expiresAt: Instant = fixedClock.instant().plusSeconds(3600),
        status: ApprovalStatus = ApprovalStatus.PENDING,
        version: Long = 0L,
    ) = ApprovalRequest(
        approvalId = approvalId,
        binding = ApprovalBinding(
            workflowRunId = workflowRunId,
            toolName = "test-tool",
            argumentsDigest = zeroDigest,
            policyVersion = "v1",
            workflowDigest = oneDigest,
            approvalTokenDigest = twoDigest,
        ),
        status = status,
        requestedBy = requestedBy,
        requestedAt = requestedAt,
        expiresAt = expiresAt,
        decidedBy = null,
        decidedAt = null,
        decisionComment = null,
        consumedBy = null,
        consumedAt = null,
        version = version,
    )
}

/**
 * A fake [ApprovalGatewayRequestFactory] that returns deterministic persistence requests.
 */
internal class FakeApprovalGatewayRequestFactory(
    private val fixedClock: Clock,
    var defaultApprovalId: String = "gateway-test-1",
    var defaultRequestedBy: String = "test-actor",
    var defaultResumeToken: ResumeToken = ResumeToken("public-resume-token"),
    private val digester: Sha256ToolArgumentsDigester = Sha256ToolArgumentsDigester(),
) : ApprovalGatewayRequestFactory {

    private val zeroDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000000")
    private val oneDigest = Sha256Digest.of("sha256:1111111111111111111111111111111111111111111111111111111111111111")
    private val twoDigest = Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222")

    override suspend fun createRequest(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalGatewayPersistenceRequest {
        val now = fixedClock.instant()
        val wfRunId = workflowRunId?.value ?: "wf-run-1"
        val argumentJson = "{\"subject\":\"${subject.value}\"}"
        val sensitiveArgs = SensitiveToolArguments.of(argumentJson)
        val argsDigest = digester.digest(sensitiveArgs)

        val approvalRequest = ApprovalRequest(
            approvalId = defaultApprovalId,
            binding = ApprovalBinding(
                workflowRunId = wfRunId,
                toolName = "test-tool",
                argumentsDigest = argsDigest,
                policyVersion = "v1",
                workflowDigest = oneDigest,
                approvalTokenDigest = twoDigest,
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = defaultRequestedBy,
            requestedAt = now,
            expiresAt = now.plusSeconds(3600),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        val continuation = ApprovalContinuation(
            approvalId = defaultApprovalId,
            workflowRunId = wfRunId,
            correlationId = "corr-${defaultApprovalId}",
            toolCallId = "tc-1",
            toolName = "test-tool",
            argumentsDigest = argsDigest,
            policyVersion = "v1",
            workflowDigest = oneDigest,
            status = ApprovalContinuationStatus.PENDING,
            createdAt = now,
            approvalExpiresAt = now.plusSeconds(3600),
            claimedBy = null,
            claimedAt = null,
            completedAt = null,
            version = 0L,
        )

        val operationReference = ResumeOperationReference(
            serviceInterface = "dev.tramai.test.TestService",
            methodName = "testMethod",
            jvmMethodDescriptor = "(Ltest/Input;)Ltest/Output;",
            resumeDefinitionDigest = zeroDigest,
        )
        // The envelope must bind to the metadata (assistant tool-call message
        // with matching id/name/index) and carry the canonical digest, per the
        // shared SuspendedInvocationStore contract.
        val envelopeMessages = listOf(
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall(id = "tc-1", name = "test-tool", argumentsJson = "{}")),
            ),
        )
        val envelopeDigest = ReplayEnvelopeDigestHelper.compute(operationReference, envelopeMessages)

        val suspendedInvocationMetadata = SuspendedInvocationMetadata(
            approvalId = defaultApprovalId,
            toolCallId = "tc-1",
            toolName = "test-tool",
            toolCallIndex = 0,
            correlationId = "corr-${defaultApprovalId}",
            identity = EngineExecutionIdentity(
                workflowRunId = wfRunId,
                correlationId = "corr-${defaultApprovalId}",
                workflowDigest = oneDigest,
                policyVersion = "v1",
                actorId = defaultRequestedBy,
            ),
            securityContext = ExecutionSecurityContext(),
            operationReference = operationReference,
            replayEnvelopeDigest = envelopeDigest,
            toolReference = ResumeToolReference(
                toolName = "test-tool",
                declarationDigest = zeroDigest,
            ),
        )

        val replayEnvelope = SensitiveReplayEnvelope.of(envelopeMessages)

        return ApprovalGatewayPersistenceRequest(
            approvalRequest = approvalRequest,
            continuation = continuation,
            sensitiveArguments = sensitiveArgs,
            suspendedInvocationMetadata = suspendedInvocationMetadata,
            replayEnvelope = replayEnvelope,
            resumeToken = defaultResumeToken,
        )
    }
}

/**
 * A mutable [Clock] that can be advanced programmatically for time-dependent testing.
 */
internal class MutableTestClock(
    private var now: Instant,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = now
    override fun withZone(zone: ZoneId): Clock = MutableTestClock(now, zone)
    override fun getZone(): ZoneId = zone

    fun advance(amount: Duration) {
        now = now.plus(amount)
    }
}
