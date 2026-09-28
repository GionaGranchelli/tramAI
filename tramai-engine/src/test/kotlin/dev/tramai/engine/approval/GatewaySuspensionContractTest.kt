@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.GovernedSuspendedInvocationStore
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.inMemorySuspendedInvocationStore
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The collaborator contracts `DefaultApprovalGateway` must honour when a store genuinely suspends.
 *
 * Every store wired in the released gateway suites is in-memory and completes synchronously, so the
 * suspension protocol itself — the sentinel returned when a nested call suspends, and the rethrow
 * that must unwrap a failure arriving on the resumed frame — is never exercised. These tests state
 * the contract at the only level where it is observable: a collaborator suspends for real, and the
 * gateway must either complete the suspension saga or fail closed with the collaborator's own
 * failure reaching the caller.
 *
 * Scope note: the gateway documents that it has no single transactional boundary across the three
 * stores. What these tests therefore pin is that a failing write is *propagated* and never silently
 * swallowed, and that no later step of the chain runs after a failure.
 */
class GatewaySuspensionContractTest {
    private val fixedClock: Clock =
        Clock.fixed(
            Instant.parse("2026-06-25T10:00:00Z"),
            ZoneId.of("UTC"),
        )

    private lateinit var approvalStore: InMemoryApprovalStore
    private lateinit var continuationStore: InMemoryApprovalContinuationStore
    private lateinit var suspendedInvocationStore: SuspendedInvocationStore
    private lateinit var factory: FakeApprovalGatewayRequestFactory

    @BeforeEach
    fun setUp() {
        val ttl = Duration.ofHours(2)
        approvalStore = InMemoryApprovalStore(clock = fixedClock, maxCreationTtl = ttl)
        continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock, maxContinuationTtl = ttl)
        suspendedInvocationStore = inMemorySuspendedInvocationStore()
        factory = FakeApprovalGatewayRequestFactory(fixedClock, defaultApprovalId = "suspension-contract")
    }

    private fun createGateway(
        approvals: ApprovalStore = approvalStore,
        suspensions: SuspendedInvocationStore = suspendedInvocationStore,
        continuations: ApprovalContinuationStore = continuationStore,
        requestFactory: ApprovalGatewayRequestFactory = factory,
    ): DefaultApprovalGateway =
        DefaultApprovalGateway(
            approvalStore = approvals,
            continuationStore = continuations,
            suspendedInvocationStore = suspensions,
            requestFactory = requestFactory,
            clock = fixedClock,
        )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? =
        try {
            withTimeout(2_000) { block() }
            null
        } catch (e: Throwable) {
            e
        }

    private suspend fun DefaultApprovalGateway.request(workflowRunId: WorkflowRunId? = null): ApprovalRequestResult =
        withTimeout(2_000) {
            requestApproval(
                subject = ApprovalSubject("claim-42"),
                recommendation = ApprovalRecommendation("review", "Medical review required"),
                requiredRole = ApproverRole("medical-reviewer"),
                workflowRunId = workflowRunId,
            )
        }

    // -----------------------------------------------------------------------
    // Ungoverned suspension saga
    // -----------------------------------------------------------------------

    @Test
    fun `an ungoverned request completes when every persistence collaborator genuinely suspends`(): Unit =
        runBlocking {
            val approvalId = "suspending-ungoverned"
            factory.defaultApprovalId = approvalId
            val approvals = SuspendingOnWriteApprovalStore(approvalStore)
            val suspensions = SuspendingSuspendedInvocationStore(suspendedInvocationStore)
            val continuations = SuspendingContinuationStore(continuationStore)
            val requestFactory = SuspendingRequestFactory(factory)
            val gateway =
                createGateway(
                    approvals = approvals,
                    suspensions = suspensions,
                    continuations = continuations,
                    requestFactory = requestFactory,
                )

            val result = gateway.request()

            // Proof that each collaborator really suspended and the caller resumed with the value
            // it returned: a corrupted suspension protocol cannot reach this state.
            assertThat(requestFactory.resumedCalls).isEqualTo(1)
            assertThat(approvals.getResumes).isEqualTo(1)
            assertThat(approvals.writeResumes).isEqualTo(1)
            assertThat(suspensions.writeResumes).isEqualTo(1)
            assertThat(continuations.writeResumes).isEqualTo(1)

            val suspended = result as ApprovalRequestResult.Suspended
            assertThat(suspended.approvalId.value).isEqualTo(approvalId)
            assertThat(approvalStore.get(approvalId)).isNotNull
            assertThat(suspendedInvocationStore.get(approvalId)).isNotNull
            assertThat(continuationStore.get(approvalId)).isNotNull
        }

    @Test
    fun `a request factory failure after suspension reaches the caller and writes nothing`(): Unit =
        runBlocking {
            val approvalId = "factory-suspending-failure"
            factory.defaultApprovalId = approvalId
            val suspensions = SuspendingSuspendedInvocationStore(suspendedInvocationStore)
            val continuations = SuspendingContinuationStore(continuationStore)
            val requestFactory =
                SuspendingRequestFactory(
                    factory,
                    failAfterSuspension = IllegalStateException("factory-failed-after-suspension"),
                )
            val gateway =
                createGateway(
                    suspensions = suspensions,
                    continuations = continuations,
                    requestFactory = requestFactory,
                )

            val failure = captureFailure { gateway.request() }

            assertThat(requestFactory.resumedCalls).isEqualTo(1)
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure!!.message).isEqualTo("factory-failed-after-suspension")
            assertThat(approvalStore.get(approvalId)).isNull()
            assertThat(suspensions.writeResumes).isEqualTo(0)
            assertThat(continuations.writeResumes).isEqualTo(0)
        }

    @Test
    fun `an approval write failure after suspension reaches the caller and stops the chain`(): Unit =
        runBlocking {
            val approvalId = "approval-suspending-failure"
            factory.defaultApprovalId = approvalId
            val approvals =
                SuspendingOnWriteApprovalStore(
                    approvalStore,
                    failAfterWriteResumes = IllegalStateException("approval-failed-after-suspension"),
                )
            val suspensions = SuspendingSuspendedInvocationStore(suspendedInvocationStore)
            val continuations = SuspendingContinuationStore(continuationStore)
            val gateway =
                createGateway(
                    approvals = approvals,
                    suspensions = suspensions,
                    continuations = continuations,
                    requestFactory = SuspendingRequestFactory(factory),
                )

            val failure = captureFailure { gateway.request() }

            assertThat(approvals.writeResumes).isEqualTo(1)
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure!!.message).isEqualTo("approval-failed-after-suspension")
            assertThat(approvalStore.get(approvalId)).isNull()
            assertThat(suspensions.writeResumes).isEqualTo(0)
            assertThat(continuations.writeResumes).isEqualTo(0)
        }

    @Test
    fun `a suspended-invocation write failure after suspension reaches the caller and stops the chain`(): Unit =
        runBlocking {
            val approvalId = "suspension-suspending-failure"
            factory.defaultApprovalId = approvalId
            val suspensions =
                SuspendingSuspendedInvocationStore(
                    suspendedInvocationStore,
                    failAfterWriteResumes = IllegalStateException("suspension-failed-after-suspension"),
                )
            val continuations = SuspendingContinuationStore(continuationStore)
            val gateway =
                createGateway(
                    approvals = SuspendingOnWriteApprovalStore(approvalStore),
                    suspensions = suspensions,
                    continuations = continuations,
                    requestFactory = SuspendingRequestFactory(factory),
                )

            val failure = captureFailure { gateway.request() }

            assertThat(suspensions.writeResumes).isEqualTo(1)
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure!!.message).isEqualTo("suspension-failed-after-suspension")
            assertThat(continuationStore.get(approvalId)).isNull()
            assertThat(continuations.writeResumes).isEqualTo(0)
        }

    @Test
    fun `a continuation write failure after suspension reaches the caller and leaves no continuation`(): Unit =
        runBlocking {
            val approvalId = "continuation-suspending-failure"
            factory.defaultApprovalId = approvalId
            val continuations =
                SuspendingContinuationStore(
                    continuationStore,
                    failAfterWriteResumes = IllegalStateException("continuation-failed-after-suspension"),
                )
            val gateway =
                createGateway(
                    approvals = SuspendingOnWriteApprovalStore(approvalStore),
                    suspensions = SuspendingSuspendedInvocationStore(suspendedInvocationStore),
                    continuations = continuations,
                    requestFactory = SuspendingRequestFactory(factory),
                )

            val failure = captureFailure { gateway.request() }

            assertThat(continuations.writeResumes).isEqualTo(1)
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure!!.message).isEqualTo("continuation-failed-after-suspension")
            assertThat(continuationStore.get(approvalId)).isNull()
        }

    // -----------------------------------------------------------------------
    // Governed suspension saga
    // -----------------------------------------------------------------------

    @Test
    fun `a governed request completes when every governed persistence collaborator genuinely suspends`(): Unit =
        runBlocking {
            val approvalId = "gov-suspending"
            factory.defaultApprovalId = approvalId
            val governedApprovals = TestGovernedApprovalStore(approvalStore)
            val governedSuspensions = TestGovernedSuspendedInvocationStore(suspendedInvocationStore)
            val approvals = SuspendingGovernedApprovalStore(governedApprovals)
            val suspensions = SuspendingGovernedSuspendedInvocationStore(governedSuspensions)
            val continuations = SuspendingContinuationStore(continuationStore)
            val gateway =
                createGateway(
                    approvals = approvals,
                    suspensions = suspensions,
                    continuations = continuations,
                    requestFactory = SuspendingRequestFactory(factory),
                )
            val identity = governedIdentity("governed-run-1")

            val result = withContext(GovernedRunScope(identity)) { gateway.request() }

            assertThat(approvals.governedWriteResumes).isEqualTo(1)
            assertThat(suspensions.governedWriteResumes).isEqualTo(1)
            assertThat(continuations.writeResumes).isEqualTo(1)

            val suspended = result as ApprovalRequestResult.Suspended
            assertThat(suspended.workflowRunId.value).isEqualTo("governed-run-1")
            assertThat(governedApprovals.attributionOf(approvalId)).isEqualTo(ApprovalRunAttribution.Governed(identity))
            assertThat(governedSuspensions.governed.single().runIdentity).isEqualTo(identity)
            assertThat(continuationStore.get(approvalId)).isNotNull
        }

    @Test
    fun `a governed approval write failure after suspension reaches the caller and writes nothing`(): Unit =
        runBlocking {
            val approvalId = "gov-approval-failure"
            factory.defaultApprovalId = approvalId
            val governedSuspensions = TestGovernedSuspendedInvocationStore(suspendedInvocationStore)
            val approvals =
                SuspendingGovernedApprovalStore(
                    TestGovernedApprovalStore(approvalStore),
                    failAfterGovernedWriteResumes = IllegalStateException("governed-approval-failed-after-suspension"),
                )
            val suspensions = SuspendingGovernedSuspendedInvocationStore(governedSuspensions)
            val continuations = SuspendingContinuationStore(continuationStore)
            val gateway =
                createGateway(
                    approvals = approvals,
                    suspensions = suspensions,
                    continuations = continuations,
                    requestFactory = SuspendingRequestFactory(factory),
                )
            val identity = governedIdentity("governed-run-1")

            val failure = captureFailure { withContext(GovernedRunScope(identity)) { gateway.request() } }

            assertThat(approvals.governedWriteResumes).isEqualTo(1)
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure!!.message).isEqualTo("governed-approval-failed-after-suspension")
            assertThat(approvalStore.get(approvalId)).isNull()
            assertThat(suspensions.governedWriteResumes).isEqualTo(0)
            assertThat(continuations.writeResumes).isEqualTo(0)
        }

    @Test
    fun `a governed suspension write failure after suspension reaches the caller and writes no continuation`(): Unit =
        runBlocking {
            val approvalId = "gov-suspension-failure"
            factory.defaultApprovalId = approvalId
            val approvals = SuspendingGovernedApprovalStore(TestGovernedApprovalStore(approvalStore))
            val suspensions =
                SuspendingGovernedSuspendedInvocationStore(
                    TestGovernedSuspendedInvocationStore(suspendedInvocationStore),
                    failAfterGovernedWriteResumes =
                        IllegalStateException("governed-suspension-failed-after-suspension"),
                )
            val continuations = SuspendingContinuationStore(continuationStore)
            val gateway =
                createGateway(
                    approvals = approvals,
                    suspensions = suspensions,
                    continuations = continuations,
                    requestFactory = SuspendingRequestFactory(factory),
                )
            val identity = governedIdentity("governed-run-1")

            val failure = captureFailure { withContext(GovernedRunScope(identity)) { gateway.request() } }

            assertThat(suspensions.governedWriteResumes).isEqualTo(1)
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure!!.message).isEqualTo("governed-suspension-failed-after-suspension")
            assertThat(continuations.writeResumes).isEqualTo(0)
            assertThat(continuationStore.get(approvalId)).isNull()
        }

    @Test
    fun `an existing governed approval is re-validated across a suspending attribution read`(): Unit =
        runBlocking {
            val approvalId = "gov-existing-suspending-read"
            factory.defaultApprovalId = approvalId
            val identity = governedIdentity("governed-run-1")
            val governedApprovals = TestGovernedApprovalStore(approvalStore)
            val approvals = SuspendingGovernedApprovalStore(governedApprovals)
            val suspensions =
                SuspendingGovernedSuspendedInvocationStore(
                    TestGovernedSuspendedInvocationStore(suspendedInvocationStore),
                )
            val gateway =
                createGateway(
                    approvals = approvals,
                    suspensions = suspensions,
                    continuations = SuspendingContinuationStore(continuationStore),
                    requestFactory = SuspendingRequestFactory(factory),
                )

            // A durable approval carrying this run's canonical attribution already exists.
            val persistenceRequest =
                factory.createRequest(
                    subject = ApprovalSubject("claim-42"),
                    recommendation = ApprovalRecommendation("review", "Medical review required"),
                    requiredRole = ApproverRole("medical-reviewer"),
                    workflowRunId = WorkflowRunId("governed-run-1"),
                )
            governedApprovals.createGovernedApproval(
                persistenceRequest.approvalRequest,
                ApprovalRunAttribution.Governed(identity),
            )

            val result = withContext(GovernedRunScope(identity)) { gateway.request() }

            assertThat(approvals.attributionReadResumes).isEqualTo(1)
            assertThat(governedApprovals.governedCreates).isEqualTo(1)
            val suspended = result as ApprovalRequestResult.Suspended
            assertThat(suspended.approvalId.value).isEqualTo(approvalId)
        }

    @Test
    fun `a failure resuming the attribution read reaches the caller`(): Unit =
        runBlocking {
            val approvalId = "gov-attribution-read-failure"
            factory.defaultApprovalId = approvalId
            val identity = governedIdentity("governed-run-1")
            val governedApprovals = TestGovernedApprovalStore(approvalStore)
            val approvals =
                SuspendingGovernedApprovalStore(
                    governedApprovals,
                    failAfterAttributionReadResumes = IllegalStateException("attribution-read-failed-after-suspension"),
                )
            val gateway =
                createGateway(
                    approvals = approvals,
                    suspensions =
                        SuspendingGovernedSuspendedInvocationStore(
                            TestGovernedSuspendedInvocationStore(suspendedInvocationStore),
                        ),
                    continuations = SuspendingContinuationStore(continuationStore),
                    requestFactory = SuspendingRequestFactory(factory),
                )
            val persistenceRequest =
                factory.createRequest(
                    subject = ApprovalSubject("claim-42"),
                    recommendation = ApprovalRecommendation("review", "Medical review required"),
                    requiredRole = ApproverRole("medical-reviewer"),
                    workflowRunId = WorkflowRunId("governed-run-1"),
                )
            governedApprovals.createGovernedApproval(
                persistenceRequest.approvalRequest,
                ApprovalRunAttribution.Governed(identity),
            )

            val failure = captureFailure { withContext(GovernedRunScope(identity)) { gateway.request() } }

            assertThat(approvals.attributionReadResumes).isEqualTo(1)
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure!!.message).isEqualTo("attribution-read-failed-after-suspension")
        }
}

/** Delegating request factory that suspends before delegating, optionally failing after resuming. */
private class SuspendingRequestFactory(
    private val delegate: ApprovalGatewayRequestFactory,
    private val failAfterSuspension: Throwable? = null,
) : ApprovalGatewayRequestFactory {
    var resumedCalls = 0
        private set

    override suspend fun createRequest(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalGatewayPersistenceRequest {
        delay(1)
        resumedCalls++
        failAfterSuspension?.let { throw it }
        return delegate.createRequest(subject, recommendation, requiredRole, workflowRunId)
    }
}

/** Delegating approval store that suspends on both read and write, optionally failing the write. */
private class SuspendingOnWriteApprovalStore(
    private val delegate: ApprovalStore,
    private val failAfterWriteResumes: Throwable? = null,
) : ApprovalStore by delegate {
    var getResumes = 0
        private set
    var writeResumes = 0
        private set

    override suspend fun get(approvalId: String): ApprovalRequest? {
        delay(1)
        getResumes++
        return delegate.get(approvalId)
    }

    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        delay(1)
        writeResumes++
        failAfterWriteResumes?.let { throw it }
        return delegate.create(request)
    }
}

/** Delegating suspended-invocation store that suspends on write, optionally failing afterwards. */
private class SuspendingSuspendedInvocationStore(
    private val delegate: SuspendedInvocationStore,
    private val failAfterWriteResumes: Throwable? = null,
) : SuspendedInvocationStore by delegate {
    var writeResumes = 0
        private set

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        delay(1)
        writeResumes++
        failAfterWriteResumes?.let { throw it }
        delegate.create(metadata, replayEnvelope)
    }
}

/** Delegating continuation store that suspends on write, optionally failing afterwards. */
private class SuspendingContinuationStore(
    private val delegate: ApprovalContinuationStore,
    private val failAfterWriteResumes: Throwable? = null,
) : ApprovalContinuationStore by delegate {
    var writeResumes = 0
        private set

    override suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation {
        delay(1)
        writeResumes++
        failAfterWriteResumes?.let { throw it }
        return delegate.create(continuation, arguments)
    }
}

/** Governed approval capability over the shared test store, suspending on both governed operations. */
private class SuspendingGovernedApprovalStore(
    private val delegate: TestGovernedApprovalStore,
    private val failAfterGovernedWriteResumes: Throwable? = null,
    private val failAfterAttributionReadResumes: Throwable? = null,
) : GovernedApprovalStore by delegate {
    var governedWriteResumes = 0
        private set
    var attributionReadResumes = 0
        private set

    override suspend fun createGovernedApproval(
        request: ApprovalRequest,
        attribution: ApprovalRunAttribution.Governed,
    ): ApprovalRequest {
        delay(1)
        governedWriteResumes++
        failAfterGovernedWriteResumes?.let { throw it }
        return delegate.createGovernedApproval(request, attribution)
    }

    override suspend fun attributionOf(approvalId: String): ApprovalRunAttribution {
        delay(1)
        attributionReadResumes++
        failAfterAttributionReadResumes?.let { throw it }
        return delegate.attributionOf(approvalId)
    }
}

/**
 * Governed suspension capability over the shared test store: both write paths suspend, so the
 * governed path exercises the same suspension protocol as the legacy one.
 */
private class SuspendingGovernedSuspendedInvocationStore(
    private val delegate: TestGovernedSuspendedInvocationStore,
    private val failAfterGovernedWriteResumes: Throwable? = null,
) : GovernedSuspendedInvocationStore by delegate {
    var governedWriteResumes = 0
        private set

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        delay(1)
        delegate.create(metadata, replayEnvelope)
    }

    override suspend fun createGoverned(
        suspended: GovernedSuspendedInvocation,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        delay(1)
        governedWriteResumes++
        failAfterGovernedWriteResumes?.let { throw it }
        delegate.createGoverned(suspended, replayEnvelope)
    }
}
