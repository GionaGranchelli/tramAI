@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine.timeline

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
import dev.tramai.core.observation.attempt.AttemptOutcome
import dev.tramai.core.observation.attempt.AttemptRecordingObserver
import dev.tramai.core.observation.attempt.InMemoryAttemptObservationStore
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.engine.characterization.ExecutionTraceFixture
import dev.tramai.engine.characterization.TraceService
import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.GovernanceQueryService
import dev.tramai.security.timeline.AttemptHistoryState
import dev.tramai.security.timeline.CorrelationEvidence
import dev.tramai.security.timeline.GovernanceSection
import dev.tramai.security.timeline.SemanticTimeline
import dev.tramai.security.timeline.SemanticTimelineQuery
import dev.tramai.security.timeline.TimelineRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 0.7.5c on a real execution path: a governed, retried service call recorded by the real
 * observer, then read back through the timeline. No evidence here is built by hand — the provider
 * really is invoked, really fails and is really retried inside a governed run scope.
 */
class SemanticTimelineExecutionTest {
    private val store = InMemoryAttemptObservationStore()

    @Test
    fun `a real retried governed run becomes one invocation with a failed and a succeeded attempt`() {
        val timeline =
            runGovernedCall(RUN_A) {
                // The primary provider fails the first attempt with a retryable error.
                providerFailures = 1
            }

        assertThat(timeline.history).isEqualTo(AttemptHistoryState.RECORDED)
        assertThat(timeline.invocations).hasSize(1)

        val invocation = timeline.invocations.single()
        assertThat(invocation.attempts.map { it.attempt }).containsExactly(0, 1)
        assertThat(invocation.attempts.map { it.outcome })
            .containsExactly(AttemptOutcome.FAILED, AttemptOutcome.SUCCEEDED)
        assertThat(invocation.finalOutcome).isEqualTo(AttemptOutcome.SUCCEEDED)

        // Every attempt really was a provider call in this run and this invocation.
        assertThat(invocation.attempts.map { it.workflowRunId }.distinct()).containsExactly(RUN_A)
        assertThat(invocation.attempts.map { it.providerId }.distinct()).containsExactly("primary")
        assertThat(invocation.correlationId).isNotNull()
    }

    @Test
    fun `repeated governed runs are separate invocations, never one merged history`() {
        val first = runGovernedCall(RUN_B) {}
        val second = runGovernedCall(RUN_C) {}

        assertThat(first.invocations).hasSize(1)
        assertThat(second.invocations).hasSize(1)
        assertThat(second.invocations.single().invocationId)
            .isNotEqualTo(first.invocations.single().invocationId)
    }

    @Test
    fun `the timeline of a real run carries no prompt content`() {
        val timeline = runGovernedCall(RUN_D) {}

        val rendered = timeline.toString() + timeline.attempts().joinToString { it.toString() }

        // The rendering is populated with real facts, so the absence below is meaningful.
        assertThat(rendered).contains("primary").contains(RUN_D)
        // The call really sent this prompt (it is the operation input above), and it is absent here.
        assertThat(rendered).doesNotContain("a request sentinel")
    }

    @Test
    fun `a run with no recorded attempt is empty history and a query records nothing`() {
        val before = store.all().size

        val empty = query().timeline(TimelineRequest("run-without-attempts"))

        assertThat(empty.history).isEqualTo(AttemptHistoryState.EMPTY)
        assertThat(empty.invocations).isEmpty()
        assertThat(empty.usage).isNull()
        // Reading is not recording.
        assertThat(store.all()).hasSize(before)
    }

    @Test
    fun `governance is reported beside attempts and correlated only by the run identity`() {
        runGovernedCall(RUN_E) {}
        val scope = GovernanceQueryRequest("claims", "production", "eu-west-amsterdam-01", RUN_E)

        val timeline =
            query(GovernanceQueryService { GovernanceQueryResult.NotMaterialized })
                .timeline(TimelineRequest(RUN_E, scope))

        assertThat(timeline.governance).isEqualTo(GovernanceSection.NotMaterialized)
        assertThat(timeline.correlation).isEqualTo(CorrelationEvidence.NONE)
        // The attempts are still reported: a governance gap does not erase observations.
        assertThat(timeline.invocations).hasSize(1)

        val unasked = query().timeline(TimelineRequest(RUN_E))
        assertThat(unasked.governance).isEqualTo(GovernanceSection.NotQueried)
        assertThat(unasked.correlation).isEqualTo(CorrelationEvidence.NONE)
    }

    // ---------------------------------------------------------------- fixtures

    private fun query(governance: GovernanceQueryService? = null) = SemanticTimelineQuery.of(store, governance)

    /** Runs one real governed call and returns the timeline read back from what it recorded. */
    private fun runGovernedCall(
        runId: String,
        configure: ExecutionTraceFixture.() -> Unit,
    ): SemanticTimeline =
        runBlocking {
            val fixture =
                ExecutionTraceFixture().apply {
                    // The engine binds this observer to each attempt; the test never invents an id.
                    observerOverride = AttemptRecordingObserver(store)
                    configure()
                }
            val service = fixture.engine().create(TraceService::class)
            withContext(GovernedRunScope(identity(runId))) { service.retryOnce("a request sentinel") }
            query().timeline(TimelineRequest(runId))
        }

    private fun identity(runId: String): GovernedRunIdentity =
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

    private companion object {
        const val RUN_A = "run-timeline-a"
        const val RUN_B = "run-timeline-b"
        const val RUN_C = "run-timeline-c"
        const val RUN_D = "run-timeline-d"
        const val RUN_E = "run-timeline-e"
    }
}
