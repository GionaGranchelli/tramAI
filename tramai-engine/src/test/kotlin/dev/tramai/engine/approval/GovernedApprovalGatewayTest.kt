@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.GovernedRunContinuityException
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.GovernedSuspendedInvocationStore
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.approval.ApprovalRunAttribution.Governed
import dev.tramai.engine.approval.ApprovalRunAttribution.Ungoverned
import dev.tramai.engine.inMemorySuspendedInvocationStore
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertFailsWith

/**
 * 0.7.1d1 P3: the preview approval gateway carries a governed run's canonical identity.
 *
 * The gateway resolves the scope once and treats the identity as the single value for the request:
 * the request factory stays identity-blind and is merely handed the canonical run id, while the
 * approval attribution is derived at the governed store boundary and the same identity goes into the
 * governed suspension record.
 *
 * These discriminators pin the boundaries that make that safe: capability discovery before the
 * factory runs, no fallback to the legacy create paths, no adoption of an approval whose durable
 * attribution disagrees with the active run, and no writing of anything before those checks pass.
 *
 * Kept in its own class rather than appended to the released gateway suite so that both stay
 * readable; it owns its own stores and reuses the shared fake request factory.
 */
class GovernedApprovalGatewayTest {
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
        factory = FakeApprovalGatewayRequestFactory(fixedClock, defaultApprovalId = "governed-gateway-test")
    }

    private fun createGateway(
        approvals: ApprovalStore = approvalStore,
        suspensions: SuspendedInvocationStore = suspendedInvocationStore,
        continuations: ApprovalContinuationStore = continuationStore,
    ): DefaultApprovalGateway =
        DefaultApprovalGateway(
            approvalStore = approvals,
            continuationStore = continuations,
            suspendedInvocationStore = suspensions,
            requestFactory = factory,
            clock = fixedClock,
        )

    @Test
    fun `a governed request derives the canonical run id for the factory when the caller omits it`(): Unit =
        runBlocking {
            val approvalId = "gov-derived"
            factory.defaultApprovalId = approvalId
            val governedApprovals = TestGovernedApprovalStore(approvalStore)
            val governedSuspensions = TestGovernedSuspendedInvocationStore(suspendedInvocationStore)
            val gateway = createGateway(approvals = governedApprovals, suspensions = governedSuspensions)
            val identity = governedIdentity("governed-run-1")

            val result =
                withContext(GovernedRunScope(identity)) {
                    gateway.requestApproval(
                        subject = ApprovalSubject("claim-42"),
                        recommendation = ApprovalRecommendation("review", "Medical review required"),
                        requiredRole = ApproverRole("medical-reviewer"),
                    )
                }

            // The factory stays identity-blind, but it must be handed the canonical run id rather
            // than nothing: an omitted argument is derived, not left absent.
            assertThat(factory.lastRequestedWorkflowRunId?.value).isEqualTo("governed-run-1")

            val suspended = result as ApprovalRequestResult.Suspended
            assertThat(suspended.workflowRunId.value).isEqualTo("governed-run-1")
            assertThat(governedApprovals.attributionOf(approvalId)).isEqualTo(Governed(identity))
            assertThat(governedSuspensions.governed.single().runIdentity).isEqualTo(identity)
        }

    @Test
    fun `a governed request persists through the governed capabilities and never the legacy ones`(): Unit =
        runBlocking {
            val approvalId = "gov-happy"
            factory.defaultApprovalId = approvalId
            val governedApprovals = TestGovernedApprovalStore(approvalStore)
            val governedSuspensions = TestGovernedSuspendedInvocationStore(suspendedInvocationStore)
            val gateway = createGateway(approvals = governedApprovals, suspensions = governedSuspensions)
            val identity = governedIdentity("governed-run-1")

            withContext(GovernedRunScope(identity)) {
                gateway.requestApproval(
                    subject = ApprovalSubject("claim-42"),
                    recommendation = ApprovalRecommendation("review", "Medical review required"),
                    requiredRole = ApproverRole("medical-reviewer"),
                    workflowRunId = WorkflowRunId("governed-run-1"),
                )
            }

            assertThat(governedApprovals.governedCreates).isEqualTo(1)
            assertThat(governedSuspensions.governed.size).isEqualTo(1)
            assertThat(governedSuspensions.governed.single().runIdentity).isEqualTo(identity)
            // No fallback: a governed request never touches either legacy create path.
            assertThat(governedApprovals.legacyCreates).isZero()
            assertThat(governedSuspensions.legacyCreates).isZero()
        }

    @Test
    fun `a governed request against legacy-only wiring fails configuration with no factory call and no writes`(): Unit =
        runBlocking {
            val approvalId = "gov-legacy-wiring"
            factory.defaultApprovalId = approvalId
            val plainApprovals = CountingPlainApprovalStore(approvalStore)
            val plainSuspensions = CountingPlainSuspendedInvocationStore(suspendedInvocationStore)
            val continuations = CountingContinuationStore(continuationStore)
            val gateway =
                createGateway(
                    approvals = plainApprovals,
                    suspensions = plainSuspensions,
                    continuations = continuations,
                )

            val thrown =
                assertFailsWith<ConfigurationException> {
                    withContext(GovernedRunScope(governedIdentity("governed-run-1"))) {
                        gateway.requestApproval(
                            subject = ApprovalSubject("claim-42"),
                            recommendation = ApprovalRecommendation("review", "Medical review required"),
                            requiredRole = ApproverRole("medical-reviewer"),
                        )
                    }
                }

            assertThat(thrown.message).contains("GovernedApprovalStore")
            // A deployment that cannot persist governed records has no reason to invoke the factory.
            assertThat(factory.calls).isZero()
            assertThat(plainApprovals.legacyCreates).isZero()
            assertThat(plainSuspensions.legacyCreates).isZero()
            assertThat(continuations.creates).isZero()
            assertThat(approvalStore.get(approvalId)).isNull()
            assertThat(suspendedInvocationStore.get(approvalId)).isNull()
        }

    @Test
    fun `a partially governed wiring fails as one precondition before the factory and before any write`(): Unit =
        runBlocking {
            val approvalId = "gov-partial-wiring"
            factory.defaultApprovalId = approvalId
            val governedApprovals = TestGovernedApprovalStore(approvalStore)
            val plainSuspensions = CountingPlainSuspendedInvocationStore(suspendedInvocationStore)
            val continuations = CountingContinuationStore(continuationStore)
            val gateway =
                createGateway(
                    approvals = governedApprovals,
                    suspensions = plainSuspensions,
                    continuations = continuations,
                )

            val thrown =
                assertFailsWith<ConfigurationException> {
                    withContext(GovernedRunScope(governedIdentity("governed-run-1"))) {
                        gateway.requestApproval(
                            subject = ApprovalSubject("claim-42"),
                            recommendation = ApprovalRecommendation("review", "Medical review required"),
                            requiredRole = ApproverRole("medical-reviewer"),
                        )
                    }
                }

            // Capability discovery is one precondition: the approval capability being present must
            // not let the request commit an approval and only then refuse the suspension.
            assertThat(thrown.message).contains("GovernedSuspendedInvocationStore")
            assertThat(factory.calls).isZero()
            assertThat(governedApprovals.governedCreates).isZero()
            assertThat(governedApprovals.legacyCreates).isZero()
            assertThat(plainSuspensions.legacyCreates).isZero()
            assertThat(continuations.creates).isZero()
        }

    @Test
    fun `a factory that re-points the binding aborts before anything durable exists`(): Unit =
        runBlocking {
            val approvalId = "gov-factory-drift"
            factory.defaultApprovalId = approvalId
            factory.overrideWorkflowRunId = "somewhere-else"
            val governedApprovals = TestGovernedApprovalStore(approvalStore)
            val governedSuspensions = TestGovernedSuspendedInvocationStore(suspendedInvocationStore)
            val gateway = createGateway(approvals = governedApprovals, suspensions = governedSuspensions)

            val thrown =
                assertFailsWith<GovernedRunContinuityException> {
                    withContext(GovernedRunScope(governedIdentity("governed-run-1"))) {
                        gateway.requestApproval(
                            subject = ApprovalSubject("claim-42"),
                            recommendation = ApprovalRecommendation("review", "Medical review required"),
                            requiredRole = ApproverRole("medical-reviewer"),
                        )
                    }
                }

            assertThat(thrown.message).contains("somewhere-else")
            assertThat(governedApprovals.governedCreates).isZero()
            assertThat(governedSuspensions.governed).isEmpty()
            assertThat(approvalStore.get(approvalId)).isNull()
        }

    @Test
    fun `an existing un-attributed approval is not adopted by a governed run`(): Unit =
        runBlocking {
            val approvalId = "gov-existing-legacy"
            factory.defaultApprovalId = approvalId
            val governedApprovals = TestGovernedApprovalStore(approvalStore)
            val gateway = createGateway(approvals = governedApprovals)
            approvalStore.create(pendingRequest(approvalId, "governed-run-1"))

            val thrown =
                assertFailsWith<GovernedRunContinuityException> {
                    withContext(GovernedRunScope(governedIdentity("governed-run-1"))) {
                        gateway.requestApproval(
                            subject = ApprovalSubject("claim-42"),
                            recommendation = ApprovalRecommendation("review", "Medical review required"),
                            requiredRole = ApproverRole("medical-reviewer"),
                        )
                    }
                }

            assertThat(thrown.message).contains(approvalId)
            assertThat(governedApprovals.governedCreates).isZero()
        }

    @Test
    fun `an existing approval governed by another identity is not adopted`(): Unit =
        runBlocking {
            val approvalId = "gov-existing-other"
            factory.defaultApprovalId = approvalId
            val governedApprovals = TestGovernedApprovalStore(approvalStore)
            val gateway = createGateway(approvals = governedApprovals)
            governedApprovals.createGovernedApproval(
                pendingRequest(approvalId, "governed-run-other"),
                Governed(governedIdentity("governed-run-other")),
            )

            val thrown =
                assertFailsWith<GovernedRunContinuityException> {
                    withContext(GovernedRunScope(governedIdentity("governed-run-1"))) {
                        gateway.requestApproval(
                            subject = ApprovalSubject("claim-42"),
                            recommendation = ApprovalRecommendation("review", "Medical review required"),
                            requiredRole = ApproverRole("medical-reviewer"),
                            workflowRunId = WorkflowRunId("governed-run-1"),
                        )
                    }
                }

            assertThat(thrown.message).contains(approvalId)
            assertThat(governedApprovals.governedCreates).isEqualTo(1)
        }

    @Test
    fun `an existing approval carrying the canonical identity returns the idempotent result`(): Unit =
        runBlocking {
            val approvalId = "gov-existing-canonical"
            factory.defaultApprovalId = approvalId
            val governedApprovals = TestGovernedApprovalStore(approvalStore)
            val governedSuspensions = TestGovernedSuspendedInvocationStore(suspendedInvocationStore)
            val gateway = createGateway(approvals = governedApprovals, suspensions = governedSuspensions)
            val identity = governedIdentity("governed-run-1")
            governedApprovals.createGovernedApproval(pendingRequest(approvalId, "governed-run-1"), Governed(identity))

            val result =
                withContext(GovernedRunScope(identity)) {
                    gateway.requestApproval(
                        subject = ApprovalSubject("claim-42"),
                        recommendation = ApprovalRecommendation("review", "Medical review required"),
                        requiredRole = ApproverRole("medical-reviewer"),
                    )
                }

            assertThat(result).isInstanceOf(ApprovalRequestResult.Suspended::class.java)
            // Idempotent: the existing approval is returned rather than re-created.
            assertThat(governedApprovals.governedCreates).isEqualTo(1)
            assertThat(governedApprovals.legacyCreates).isZero()
            assertThat(governedSuspensions.governed).isEmpty()
        }

    private fun pendingRequest(
        approvalId: String,
        workflowRunId: String,
    ): ApprovalRequest {
        val now = fixedClock.instant()
        return ApprovalRequest(
            approvalId = approvalId,
            binding =
                ApprovalBinding(
                    workflowRunId = workflowRunId,
                    toolName = "test-tool",
                    argumentsDigest = SEEDED_DIGEST,
                    policyVersion = "v1",
                    workflowDigest = SEEDED_DIGEST,
                    approvalTokenDigest = SEEDED_DIGEST,
                ),
            status = ApprovalStatus.PENDING,
            requestedBy = "test-actor",
            requestedAt = now,
            expiresAt = now.plusSeconds(3600),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )
    }
}

private val SEEDED_DIGEST: Sha256Digest =
    Sha256Digest.of("sha256:3333333333333333333333333333333333333333333333333333333333333333")

internal fun governedIdentity(runId: String): GovernedRunIdentity =
    GovernedRunIdentity(
        deployment =
            WorkloadDeploymentIdentity(
                workloadId = WorkloadId("claims"),
                configuration =
                    WorkloadConfigurationIdentity(
                        id = ConfigurationId("claims-prod"),
                        version = ConfigurationVersion("17"),
                    ),
                environmentId = EnvironmentId("production"),
                deploymentId = DeploymentId("eu-west-amsterdam-01"),
            ),
        runId = RunId(runId),
    )

/** Governed approval capability over the shared in-memory store, counting both write paths. */
internal class TestGovernedApprovalStore(
    private val delegate: ApprovalStore,
) : ApprovalStore by delegate,
    GovernedApprovalStore {
    var legacyCreates = 0
    var governedCreates = 0
    private val attribution = mutableMapOf<String, ApprovalRunAttribution>()

    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        legacyCreates++
        return delegate.create(request)
    }

    override suspend fun createGovernedApproval(
        request: ApprovalRequest,
        attribution: Governed,
    ): ApprovalRequest {
        requireAttributionMatchesBinding(request, attribution)
        governedCreates++
        this.attribution[request.approvalId] = attribution
        return delegate.create(request)
    }

    override suspend fun attributionOf(approvalId: String): ApprovalRunAttribution {
        delegate.get(approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)
        return attribution[approvalId] ?: ApprovalRunAttribution.Ungoverned
    }
}

internal class CountingPlainApprovalStore(
    private val delegate: ApprovalStore,
) : ApprovalStore by delegate {
    var legacyCreates = 0

    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        legacyCreates++
        return delegate.create(request)
    }
}

/** Governed suspension capability, counting which write path was taken. */
internal class TestGovernedSuspendedInvocationStore(
    private val delegate: SuspendedInvocationStore,
) : SuspendedInvocationStore by delegate,
    GovernedSuspendedInvocationStore {
    var legacyCreates = 0
    val governed = mutableListOf<GovernedSuspendedInvocation>()

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        legacyCreates++
        delegate.create(metadata, replayEnvelope)
    }

    override suspend fun createGoverned(
        suspended: GovernedSuspendedInvocation,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        governed += suspended
        delegate.create(suspended.metadata, replayEnvelope)
    }

    override suspend fun governedRunIdentity(approvalId: String): GovernedRunIdentity? =
        governed.firstOrNull { it.metadata.approvalId == approvalId }?.runIdentity
}

internal class CountingPlainSuspendedInvocationStore(
    private val delegate: SuspendedInvocationStore,
) : SuspendedInvocationStore by delegate {
    var legacyCreates = 0

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        legacyCreates++
        delegate.create(metadata, replayEnvelope)
    }
}

internal class CountingContinuationStore(
    private val delegate: ApprovalContinuationStore,
) : ApprovalContinuationStore by delegate {
    var creates = 0

    override suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation {
        creates++
        return delegate.create(continuation, arguments)
    }
}
