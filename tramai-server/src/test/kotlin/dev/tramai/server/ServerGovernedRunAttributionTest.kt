package dev.tramai.server

import dev.tramai.controlplane.ConfigurationFingerprint
import dev.tramai.controlplane.InMemoryWorkloadRegistrationStore
import dev.tramai.controlplane.LifecycleTransitionOutcome
import dev.tramai.controlplane.WorkloadAdmissionRejectedException
import dev.tramai.controlplane.WorkloadLifecycleState
import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.controlplane.WorkloadStateVersion
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.identity.WorkloadMetadata
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.recoverGovernedRun
import dev.tramai.orchestration.workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.7.1d server-level governed runs.
 *
 * Two rules, deliberately different:
 *
 * - NEW execution: the configured deployment must BE the authoritative registration (ACTIVE).
 * - CONTINUATION: the identity persisted with the run is authoritative; the configured
 *   deployment must merely match it. Lifecycle is never re-consulted, because a continuation
 *   is not a new execution.
 */
class ServerGovernedRunAttributionTest {
    private companion object {
        const val FAST = "governed-fast"
        const val DELAYED = "governed-delayed"
        const val CONFIG_VERSION = "17"
        const val DEPLOYMENT = "eu-west-amsterdam-01"

        const val FAST_WAIT_MS = 10_000L
        const val DELAY_MS = 200L
    }

    private object StringStateCodec : WorkflowStateCodec<String> {
        override fun encode(state: String): String = state

        override fun decode(payload: String): String = payload
    }

    private object PassthroughSignatureVerifier : WebhookSignatureVerifier {
        override val name: String = "passthrough"

        override fun verify(
            payload: String,
            headers: Map<String, String>,
        ): Boolean = true
    }

    private fun deployment(
        deploymentId: String = DEPLOYMENT,
        configurationVersion: String = CONFIG_VERSION,
    ): WorkloadDeploymentIdentity =
        WorkloadDeploymentIdentity(
            workloadId = WorkloadId("claims"),
            configuration =
                WorkloadConfigurationIdentity(
                    id = ConfigurationId("claims-prod"),
                    version = ConfigurationVersion(configurationVersion),
                ),
            environmentId = EnvironmentId("production"),
            deploymentId = DeploymentId(deploymentId),
        )

    private class Fixture {
        val runStore = WorkflowRunStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = WorkflowRegistry()
        val fastCheckpoints = InMemoryWorkflowCheckpointStore()
        val delayedCheckpoints = InMemoryWorkflowCheckpointStore()
        val fastExecutions = AtomicInteger()
        val delayedSteps = CopyOnWriteArrayList<String>()

        init {
            registry.register(
                workflow =
                    workflow<String>(name = FAST, definitionVersion = "1.0.0") {
                        localStep("count") { state, _ ->
                            fastExecutions.incrementAndGet()
                            "$state-done"
                        }
                    }.build { it },
                stateCodec = StringStateCodec,
                defaultPersistence = {
                    WorkflowPersistence(
                        checkpointStore = fastCheckpoints,
                        stateCodec = StringStateCodec,
                        deleteCheckpointOnCompletion = false,
                    )
                },
            )
            registry.register(
                workflow =
                    workflow<String>(name = DELAYED, definitionVersion = "1.0.0") {
                        delayStep("pause", DELAY_MS, TimeUnit.MILLISECONDS)
                        localStep("after") { state, _ ->
                            delayedSteps += state
                            "$state-after"
                        }
                    }.build { it },
                stateCodec = StringStateCodec,
                defaultPersistence = {
                    WorkflowPersistence(
                        checkpointStore = delayedCheckpoints,
                        stateCodec = StringStateCodec,
                        deleteCheckpointOnCompletion = false,
                    )
                },
            )
        }

        fun controller(governance: ServerGovernance): WorkflowController =
            WorkflowController(
                registry = registry,
                runStore = runStore,
                workflowExecutionScope = scope,
                signatureVerifier = PassthroughSignatureVerifier,
            ).also { it.serverGovernance = governance }

        fun waitForStatus(
            workflowName: String,
            workflowId: String,
            expected: WorkflowRunStatus,
        ) {
            val deadline = System.currentTimeMillis() + FAST_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (runStore.get(workflowName, workflowId).status == expected) return
                Thread.sleep(10)
            }
            throw AssertionError(
                "run $workflowId of $workflowName did not reach $expected " +
                    "(was ${runStore.get(workflowName, workflowId).status})",
            )
        }

        fun persistenceOf(workflowName: String): WorkflowPersistence<String> =
            if (workflowName == FAST) {
                WorkflowPersistence(
                    checkpointStore = fastCheckpoints,
                    stateCodec = StringStateCodec,
                    deleteCheckpointOnCompletion = false,
                )
            } else {
                WorkflowPersistence(
                    checkpointStore = delayedCheckpoints,
                    stateCodec = StringStateCodec,
                    deleteCheckpointOnCompletion = false,
                )
            }
    }

    private fun registered(
        identity: WorkloadDeploymentIdentity = deployment(),
        lifecycle: WorkloadLifecycleState = WorkloadLifecycleState.ACTIVE,
    ): WorkloadRegistrationAuthority {
        val store = InMemoryWorkloadRegistrationStore()
        val authority = WorkloadRegistrationAuthority(store)
        runBlocking {
            authority.register(
                identity = identity,
                configurationFingerprint = ConfigurationFingerprint("fp-claims-prod-1"),
                metadata = WorkloadMetadata(owner = "team-claims", purpose = "claims processing"),
            )
            if (lifecycle != WorkloadLifecycleState.ACTIVE) {
                val current = store.find(identity.workloadId, identity.environmentId, identity.deploymentId)!!
                authority.transitionLifecycle(
                    workloadId = identity.workloadId,
                    environmentId = identity.environmentId,
                    deploymentId = identity.deploymentId,
                    expectedVersion = current.stateVersion,
                    target = lifecycle,
                )
            }
        }
        return authority
    }

    // ─── New execution ──────────────────────────────────────────────────────────

    @Test
    fun `a registered active deployment starts a governed run with a fresh run id`() {
        val fixture = Fixture()
        val controller = fixture.controller(ServerGovernance.of(deployment(), registered()))

        val response = controller.runWorkflow(FAST, "seed", idempotencyKey = null)
        fixture.waitForStatus(FAST, response.workflowId, WorkflowRunStatus.COMPLETED)

        val recovered =
            runBlocking { fixture.persistenceOf(FAST).recoverGovernedRun(FAST, response.workflowId) }
        assertThat(recovered).isNotNull
        assertThat(recovered!!.identity.deployment).isEqualTo(deployment())
        // The run id IS the canonical RunId: no second identifier is generated.
        assertThat(recovered.identity.runId.value).isEqualTo(response.workflowId)
    }

    @Test
    fun `two governed runs of the same deployment get distinct run ids`() {
        val fixture = Fixture()
        val controller = fixture.controller(ServerGovernance.of(deployment(), registered()))

        val first = controller.runWorkflow(FAST, "one", idempotencyKey = null)
        val second = controller.runWorkflow(FAST, "two", idempotencyKey = null)

        assertThat(first.workflowId).isNotEqualTo(second.workflowId)
        fixture.waitForStatus(FAST, second.workflowId, WorkflowRunStatus.COMPLETED)
    }

    @Test
    fun `an unregistered deployment creates no run record and executes nothing`() {
        val fixture = Fixture()
        val controller =
            fixture.controller(ServerGovernance.of(deployment(deploymentId = "eu-central-frankfurt-01"), registered()))

        assertThrows<WorkloadAdmissionRejectedException> {
            controller.runWorkflow(FAST, "seed", idempotencyKey = null)
        }

        assertThat(fixture.runStore.list(FAST, offset = 0, limit = 50)).isEmpty()
        assertThat(fixture.fastExecutions.get()).isZero()
    }

    @Test
    fun `a suspended or retired deployment cannot start a new run`() {
        listOf(WorkloadLifecycleState.SUSPENDED, WorkloadLifecycleState.RETIRED).forEach { lifecycle ->
            val fixture = Fixture()
            val controller =
                fixture.controller(ServerGovernance.of(deployment(), registered(lifecycle = lifecycle)))

            val failure =
                assertThrows<WorkloadAdmissionRejectedException> {
                    controller.runWorkflow(FAST, "seed", idempotencyKey = null)
                }

            assertThat(failure.reason).isEqualTo("workload-deployment-not-active")
            assertThat(fixture.runStore.list(FAST, offset = 0, limit = 50)).isEmpty()
        }
    }

    @Test
    fun `a configured identity differing only by configuration version is rejected`() {
        val fixture = Fixture()
        val controller =
            fixture.controller(ServerGovernance.of(deployment(configurationVersion = "18"), registered()))

        val failure =
            assertThrows<WorkloadAdmissionRejectedException> {
                controller.runWorkflow(FAST, "seed", idempotencyKey = null)
            }

        assertThat(failure.reason).isEqualTo("workload-deployment-configuration-mismatch")
        assertThat(fixture.runStore.list(FAST, offset = 0, limit = 50)).isEmpty()
    }

    @Test
    fun `an idempotent retry succeeds even after the registration is suspended`() {
        val fixture = Fixture()
        val authority = registered()
        val controller = fixture.controller(ServerGovernance.of(deployment(), authority))

        val first = controller.runWorkflow(FAST, "seed", idempotencyKey = "idem-1")
        fixture.waitForStatus(FAST, first.workflowId, WorkflowRunStatus.COMPLETED)
        runBlocking {
            val outcome =
                authority.transitionLifecycle(
                    workloadId = WorkloadId("claims"),
                    environmentId = EnvironmentId("production"),
                    deploymentId = DeploymentId(DEPLOYMENT),
                    expectedVersion = WorkloadStateVersion.INITIAL,
                    target = WorkloadLifecycleState.SUSPENDED,
                )
            assertThat(outcome).isInstanceOf(LifecycleTransitionOutcome.Applied::class.java)
        }

        val retry = controller.runWorkflow(FAST, "seed", idempotencyKey = "idem-1")

        assertThat(retry.workflowId).isEqualTo(first.workflowId)
        assertThat(fixture.fastExecutions.get()).isEqualTo(1)
    }

    @Test
    fun `concurrent same-idempotency requests yield one run and one execution`() {
        val fixture = Fixture()
        val controller = fixture.controller(ServerGovernance.of(deployment(), registered()))
        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val ids = CopyOnWriteArrayList<String>()
        try {
            repeat(threads) {
                executor.submit {
                    ready.countDown()
                    go.await()
                    ids += controller.runWorkflow(FAST, "seed", idempotencyKey = "race-key").workflowId
                }
            }
            ready.await()
            go.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        } finally {
            executor.shutdownNow()
        }

        assertThat(ids.toSet()).hasSize(1)
        fixture.waitForStatus(FAST, ids.first(), WorkflowRunStatus.COMPLETED)
        assertThat(fixture.runStore.list(FAST, offset = 0, limit = 50)).hasSize(1)
        assertThat(fixture.fastExecutions.get()).isEqualTo(1)
    }

    // ─── Continuation ───────────────────────────────────────────────────────────

    private fun startDelayed(
        fixture: Fixture,
        governance: ServerGovernance,
        body: String = "seed",
    ): Pair<WorkflowController, String> {
        val controller = fixture.controller(governance)
        val response = controller.runWorkflow(DELAYED, body, idempotencyKey = null)
        fixture.waitForStatus(DELAYED, response.workflowId, WorkflowRunStatus.DELAYED)
        return controller to response.workflowId
    }

    @Test
    fun `a governed suspension and resume preserve the exact identity`() {
        val fixture = Fixture()
        val governance = ServerGovernance.of(deployment(), registered())
        val (controller, runId) = startDelayed(fixture, governance)

        val suspended =
            runBlocking { fixture.persistenceOf(DELAYED).recoverGovernedRun(DELAYED, runId) }!!
        assertThat(suspended.identity.deployment).isEqualTo(deployment())
        assertThat(suspended.identity.runId.value).isEqualTo(runId)

        Thread.sleep(DELAY_MS + 50)
        controller.resumeWorkflow(DELAYED, runId)
        fixture.waitForStatus(DELAYED, runId, WorkflowRunStatus.COMPLETED)

        val resumed = runBlocking { fixture.persistenceOf(DELAYED).recoverGovernedRun(DELAYED, runId) }!!
        assertThat(resumed.identity).isEqualTo(suspended.identity)
    }

    @Test
    fun `a lifecycle change after suspension does not block the existing run`() {
        val fixture = Fixture()
        val authority = registered()
        val governance = ServerGovernance.of(deployment(), authority)
        val (controller, runId) = startDelayed(fixture, governance)

        runBlocking {
            val outcome =
                authority.transitionLifecycle(
                    workloadId = WorkloadId("claims"),
                    environmentId = EnvironmentId("production"),
                    deploymentId = DeploymentId(DEPLOYMENT),
                    expectedVersion = WorkloadStateVersion.INITIAL,
                    target = WorkloadLifecycleState.SUSPENDED,
                )
            assertThat(outcome).isInstanceOf(LifecycleTransitionOutcome.Applied::class.java)
        }

        Thread.sleep(DELAY_MS + 50)
        controller.resumeWorkflow(DELAYED, runId)

        fixture.waitForStatus(DELAYED, runId, WorkflowRunStatus.COMPLETED)
        assertThat(fixture.delayedSteps).containsExactly("seed")
    }

    @Test
    fun `a different server deployment cannot resume the governed run`() {
        val fixture = Fixture()
        val authority = registered()
        val (_, runId) = startDelayed(fixture, ServerGovernance.of(deployment(), authority))

        val other =
            fixture.controller(
                ServerGovernance.of(deployment(deploymentId = "eu-central-frankfurt-01"), authority),
            )

        // Rejected synchronously, before any mutation: the run is still resumable afterwards.
        assertThrows<WorkflowConflictException> { other.resumeWorkflow(DELAYED, runId) }

        assertThat(fixture.runStore.get(DELAYED, runId).status).isEqualTo(WorkflowRunStatus.DELAYED)
        assertThat(fixture.delayedSteps).isEmpty()
    }

    @Test
    fun `an ungoverned server cannot resume a governed checkpoint`() {
        val fixture = Fixture()
        val (_, runId) = startDelayed(fixture, ServerGovernance.of(deployment(), registered()))

        val ungoverned = fixture.controller(ServerGovernance.UNSET)

        assertThrows<WorkflowConflictException> { ungoverned.resumeWorkflow(DELAYED, runId) }

        assertThat(fixture.runStore.get(DELAYED, runId).status).isEqualTo(WorkflowRunStatus.DELAYED)
        assertThat(fixture.delayedSteps).isEmpty()
    }

    @Test
    fun `concurrent resumes admit exactly one winner`() {
        val fixture = Fixture()
        val (controller, runId) = startDelayed(fixture, ServerGovernance.of(deployment(), registered()))
        // Let the delay elapse first: otherwise the winner's resume legitimately re-suspends.
        Thread.sleep(DELAY_MS + 50)

        val threads = 2
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val outcomes = CopyOnWriteArrayList<String>()
        try {
            repeat(threads) {
                executor.submit {
                    ready.countDown()
                    go.await()
                    val outcome = runCatching { controller.resumeWorkflow(DELAYED, runId) }
                    outcomes += outcome.fold({ "admitted" }, { it::class.simpleName ?: "failure" })
                }
            }
            ready.await()
            go.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        } finally {
            executor.shutdownNow()
        }

        // Authorization and recovery mutate nothing, so both callers reach them; only one may
        // win the admitted-resume transition, and the run executes exactly once.
        assertThat(outcomes.count { it == "admitted" }).isEqualTo(1)
        assertThat(outcomes).anyMatch { it == "WorkflowConflictException" }
        fixture.waitForStatus(DELAYED, runId, WorkflowRunStatus.COMPLETED)
        assertThat(fixture.delayedSteps).containsExactly("seed")
    }

    @Test
    fun `a legacy run stays legacy even on a governed server`() {
        val fixture = Fixture()
        val (_, runId) = startDelayed(fixture, ServerGovernance.UNSET)
        assertThat(
            runBlocking { fixture.persistenceOf(DELAYED).recoverGovernedRun(DELAYED, runId) },
        ).isNull()

        val governed = fixture.controller(ServerGovernance.of(deployment(), registered()))
        Thread.sleep(DELAY_MS + 50)
        governed.resumeWorkflow(DELAYED, runId)

        fixture.waitForStatus(DELAYED, runId, WorkflowRunStatus.COMPLETED)
        assertThat(fixture.delayedSteps).containsExactly("seed")
    }

    // ─── Configuration ──────────────────────────────────────────────────────────

    @Test
    fun `an ungoverned server stays byte-identical and partial configuration fails closed`() {
        assertThat(
            serverGovernanceFrom(
                properties =
                    ServerGovernanceProperties(
                        workloadId = null,
                        configurationId = null,
                        configurationVersion = null,
                        environmentId = null,
                        deploymentId = null,
                    ),
                authority = null,
            ),
        ).isSameAs(ServerGovernance.UNSET)

        // Partial configuration is a typo, not a choice: starting ungoverned would silently
        // strip attribution from every run of this server.
        assertThrows<IllegalArgumentException> {
            serverGovernanceFrom(
                properties =
                    ServerGovernanceProperties(
                        workloadId = "claims",
                        configurationId = null,
                        configurationVersion = null,
                        environmentId = "production",
                        deploymentId = DEPLOYMENT,
                    ),
                authority = registered(),
            )
        }

        // Governed configuration without an authoritative registration source cannot be trusted.
        assertThrows<IllegalStateException> {
            serverGovernanceFrom(
                properties =
                    ServerGovernanceProperties(
                        workloadId = "claims",
                        configurationId = "claims-prod",
                        configurationVersion = CONFIG_VERSION,
                        environmentId = "production",
                        deploymentId = DEPLOYMENT,
                    ),
                authority = null,
            )
        }
    }
}
