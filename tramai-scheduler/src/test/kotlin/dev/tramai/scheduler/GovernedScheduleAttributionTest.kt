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
        val schedules = InMemoryWorkflowSchedulerStore()
        val bindings = InMemoryGovernedScheduleBindingStore()

        /**
         * The timer discovers the binding capability from the STORE, so the durable binding
         * is consulted independently of whatever the current registration declares.
         */
        val store = CompositeWorkflowSchedulerStore(schedules, bindings)
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
        val timer = newTimer("owner-1")

        /** A second process over the same durable stores: restart, not a new schedule. */
        fun newTimer(owner: String): ScheduledWorkflowTimer =
            ScheduledWorkflowTimer(
                store = store,
                clock = clock,
                ownerId = owner,
                pollInterval = Duration.ofMillis(1),
                claimDuration = Duration.ofMinutes(5),
                misfireThreshold = Duration.ofHours(1),
            )

        /** The schedule id the timer derives from the workflow name. */
        suspend fun scheduleId(): String = store.listScheduleStatus().single().scheduleId

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

    /** A declaration that claims governance while nothing durable is written. */
    private class UnavailableBindingStore : GovernedScheduleBindingStore {
        override suspend fun putGovernedScheduleBinding(binding: GovernedScheduleBinding) = Unit

        override suspend fun getGovernedScheduleBinding(scheduleId: String): GovernedScheduleBinding? = null
    }

    /** A binding authority that is down: the write fails. */
    private class FailingBindingStore : GovernedScheduleBindingStore {
        override suspend fun putGovernedScheduleBinding(binding: GovernedScheduleBinding) {
            error("binding store unavailable")
        }

        override suspend fun getGovernedScheduleBinding(scheduleId: String): GovernedScheduleBinding? = null
    }

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
                    scheduleId = fixture.scheduleId(),
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

    @Test
    fun `an ordinary re-registration cannot downgrade a governed schedule`() {
        runBlocking {
            val fixture = Fixture()
            fixture.registerGoverned(deployment())
            val firstRunId = fixture.fireTick()

            // The in-memory registration is replaced by one that declares no governance. The
            // durable binding still exists, so the next tick must stay governed.
            fixture.timer.register(
                workflow = fixture.workflow,
                initialState = { "seed" },
                observer = NoOpWorkflowObserver,
                persistence = fixture.persistence,
            )
            val secondRunId = fixture.fireTick()

            assertThat(secondRunId).isNotEqualTo(firstRunId)
            val second = fixture.persistence.recoverGovernedRun(WORKFLOW_NAME, secondRunId)
            assertThat(second)
                .withFailMessage("a durable binding must survive an ordinary re-registration")
                .isNotNull()
            assertThat(second!!.identity.deployment).isEqualTo(deployment())
        }
    }

    @Test
    fun `a restarted timer cannot downgrade a surviving durable binding`() {
        runBlocking {
            val fixture = Fixture()
            fixture.registerGoverned(deployment())
            val firstRunId = fixture.fireTick()

            // Restart: a fresh process over the same durable stores registers the workflow the
            // ordinary way. The binding survives, so its ticks are still governed.
            val restarted = fixture.newTimer("owner-2")
            restarted.register(
                workflow = fixture.workflow,
                initialState = { "seed" },
                observer = NoOpWorkflowObserver,
                persistence = fixture.persistence,
            )

            fixture.clock.advanceBy(Duration.ofMinutes(1))
            restarted.pollOnce()

            val secondRunId =
                fixture.store
                    .listScheduleStatus()
                    .single()
                    .lastRunId!!
            assertThat(secondRunId).isNotEqualTo(firstRunId)
            val second = fixture.persistence.recoverGovernedRun(WORKFLOW_NAME, secondRunId)
            assertThat(second)
                .withFailMessage("a surviving durable binding must not be downgraded by a restarted timer")
                .isNotNull()
            assertThat(second!!.identity.deployment).isEqualTo(deployment())
        }
    }

    @Test
    fun `an explicitly governed registration without a durable binding executes nothing`() {
        runBlocking {
            val fixture = Fixture()
            val declared = deployment(deploymentId = "eu-central-frankfurt-01")
            fixture.timer.register(
                workflow = fixture.workflow,
                initialState = { "seed" },
                observer = NoOpWorkflowObserver,
                persistence = fixture.persistence,
                governed = GovernedScheduleRegistration(declared, UnavailableBindingStore()),
            )

            fixture.clock.advanceBy(Duration.ofMinutes(1))
            fixture.timer.pollOnce()

            assertThat(fixture.completedStates)
                .withFailMessage("a governed schedule with no durable binding must execute nothing")
                .isEmpty()
            assertThat(
                fixture.store
                    .listScheduleStatus()
                    .single()
                    .lastRunId,
            ).withFailMessage("nothing may be attributed, not even to the declared deployment")
                .isNull()
        }
    }

    @Test
    fun `a failed binding write publishes no schedule and executes nothing`() {
        runBlocking {
            val fixture = Fixture()
            assertThatThrownBy {
                runBlocking {
                    fixture.timer.register(
                        workflow = fixture.workflow,
                        initialState = { "seed" },
                        observer = NoOpWorkflowObserver,
                        persistence = fixture.persistence,
                        governed = GovernedScheduleRegistration(deployment(), FailingBindingStore()),
                    )
                }
            }.isInstanceOf(IllegalStateException::class.java)

            assertThat(fixture.store.getSchedule("workflow:$WORKFLOW_NAME"))
                .withFailMessage("a failed binding write must not leave an executable schedule")
                .isNull()

            fixture.clock.advanceBy(Duration.ofMinutes(1))
            fixture.timer.pollOnce()

            assertThat(fixture.completedStates)
                .withFailMessage("an unbound governed schedule must never become an ungoverned run")
                .isEmpty()
        }
    }
}
