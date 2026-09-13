package dev.tramai.scheduler

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.NoOpWorkflowObserver
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.recoverGovernedRun
import dev.tramai.orchestration.workflow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 0.7.1d governed scheduler attribution.
 *
 * A schedule carries a DeploymentIdentity, never a run identity: a schedule is not a run.
 * Each tick therefore creates a FRESH run id over the same deployment, while a delayed
 * wake-up RECOVERS the exact identity of the run it continues — from that run's own durable
 * checkpoint, never from whatever binding exists at wake-up time.
 */
class GovernedScheduleAttributionTest {
    private companion object {
        const val WORKFLOW_NAME = "governed-scheduled"
        const val GOVERNED_DEPLOYMENT_KEY = "checkpoint.identity.deployment"

        val stateCodec =
            object : WorkflowStateCodec<String> {
                override fun encode(state: String): String = state

                override fun decode(payload: String): String = payload
            }
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        fun advanceBy(duration: Duration) {
            current = current.plus(duration)
        }

        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this
    }

    private class Fixture {
        val clock = MutableClock(Instant.parse("2026-09-13T09:00:00Z"))
        val store = InMemoryWorkflowSchedulerStore()
        val bindings = InMemoryGovernedScheduleBindingStore()
        val checkpoints = InMemoryWorkflowCheckpointStore()
        val completedStates = mutableListOf<String>()
        val persistence =
            WorkflowPersistence(
                checkpointStore = checkpoints,
                stateCodec = stateCodec,
                delayWakeupScheduler = store,
                deleteCheckpointOnCompletion = false,
            )
        val workflow: Workflow<String, String> =
            workflow<String>(WORKFLOW_NAME) {
                schedule = CronSchedule.parse("* * * * *", ZoneId.of("UTC"))
                delayStep("wait", 60, TimeUnit.SECONDS)
                localStep("after") { state, _ ->
                    completedStates += state
                    "$state-after"
                }
            }.build(clock = clock) { it }
        val timer =
            ScheduledWorkflowTimer(
                store = store,
                clock = clock,
                ownerId = "owner-1",
                pollInterval = Duration.ofMillis(1),
                claimDuration = Duration.ofMinutes(5),
                misfireThreshold = Duration.ofHours(1),
            )

        suspend fun registerGoverned(deployment: WorkloadDeploymentIdentity) {
            timer.register(
                workflow = workflow,
                initialState = { "seed" },
                observer = NoOpWorkflowObserver,
                persistence = persistence,
                governed = GovernedScheduleRegistration(deployment, bindings),
            )
        }

        /** Claims a tick by making the schedule due, then polling. */
        suspend fun fireTick(): String {
            clock.advanceBy(Duration.ofMinutes(1))
            timer.pollOnce()
            return store.listScheduleStatus().single().lastRunId!!
        }

        suspend fun claimDelayedRun(): String = fireTick()
    }

    private fun deployment(
        deploymentId: String = "eu-west-amsterdam-01",
        configurationVersion: String = "17",
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

    @Test
    fun `a governed tick runs under the bound deployment with a fresh run id`() {
        runBlocking {
            val fixture = Fixture()
            fixture.registerGoverned(deployment())

            val runId = fixture.fireTick()

            val recovered = fixture.persistence.recoverGovernedRun(WORKFLOW_NAME, runId)
            assertThat(recovered).withFailMessage("a governed tick must persist its attribution").isNotNull()
            assertThat(recovered!!.identity.deployment).isEqualTo(deployment())
            assertThat(recovered.identity.runId.value).isEqualTo(runId)
        }
    }

    @Test
    fun `two ticks share the deployment and never share a run id`() {
        runBlocking {
            val fixture = Fixture()
            fixture.registerGoverned(deployment())

            val firstRunId = fixture.fireTick()
            val secondRunId = fixture.fireTick()

            assertThat(secondRunId).withFailMessage("every tick is a new execution").isNotEqualTo(firstRunId)
            val first = fixture.persistence.recoverGovernedRun(WORKFLOW_NAME, firstRunId)!!
            val second = fixture.persistence.recoverGovernedRun(WORKFLOW_NAME, secondRunId)!!
            assertThat(first.identity.deployment).isEqualTo(second.identity.deployment)
            assertThat(first.identity).isNotEqualTo(second.identity)
        }
    }

    @Test
    fun `a delayed wake-up continues the exact original identity even after the binding changes`() {
        runBlocking {
            val fixture = Fixture()
            fixture.registerGoverned(deployment())
            val runId = fixture.fireTick()

            // The deployment binding changes while the run is suspended: a governed
            // continuation must NOT adopt the new binding for an existing run.
            fixture.bindings.putGovernedScheduleBinding(
                GovernedScheduleBinding(
                    scheduleId =
                        fixture.store
                            .listScheduleStatus()
                            .single()
                            .scheduleId,
                    deploymentIdentity = deployment(deploymentId = "eu-central-frankfurt-01"),
                ),
            )

            fixture.clock.advanceBy(Duration.ofMinutes(2))
            fixture.timer.pollOnce()

            assertThat(fixture.completedStates)
                .withFailMessage("the delayed run must resume and complete")
                .containsExactly("seed")
            val persisted = fixture.checkpoints.load(WORKFLOW_NAME, runId)!!
            assertThat(persisted.metadata[GOVERNED_DEPLOYMENT_KEY])
                .withFailMessage("the original attribution must not be rewritten")
                .isEqualTo("eu-west-amsterdam-01")
        }
    }

    @Test
    fun `a governed delayed run with corrupted attribution fails closed`() {
        runBlocking {
            val fixture = Fixture()
            fixture.registerGoverned(deployment())
            val runId = fixture.fireTick()

            val persisted = fixture.checkpoints.load(WORKFLOW_NAME, runId)!!
            fixture.checkpoints.save(
                persisted.copy(metadata = persisted.metadata - GOVERNED_DEPLOYMENT_KEY),
                expectedRevision = persisted.revision,
            )

            fixture.clock.advanceBy(Duration.ofMinutes(2))

            assertThatThrownBy { runBlocking { fixture.timer.pollOnce() } }
                .isInstanceOf(dev.tramai.orchestration.WorkflowCheckpointCorruptionException::class.java)
            assertThat(fixture.completedStates)
                .withFailMessage("a partially attributed run must never resume as legacy")
                .isEmpty()
        }
    }

    @Test
    fun `a schedule without a governed binding still runs un-attributed`() {
        runBlocking {
            val fixture = Fixture()
            fixture.timer.register(
                workflow = fixture.workflow,
                initialState = { "seed" },
                observer = NoOpWorkflowObserver,
                persistence = fixture.persistence,
            )

            val runId = fixture.fireTick()

            assertThat(fixture.persistence.recoverGovernedRun(WORKFLOW_NAME, runId)).isNull()
        }
    }
}
