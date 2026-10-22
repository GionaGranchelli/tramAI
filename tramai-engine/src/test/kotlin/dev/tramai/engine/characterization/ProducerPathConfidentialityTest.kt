@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine.characterization

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
import dev.tramai.core.observation.attempt.ModelAttemptObservation
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.security.reconstruction.EvidenceBackedReconstructor
import dev.tramai.security.reconstruction.HistoricalConfigurationReference
import dev.tramai.security.reconstruction.HistoricalConfigurationSource
import dev.tramai.security.timeline.SemanticTimelineQuery
import dev.tramai.security.timeline.TimelineRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Gate 1 of Epic 0.7.5g — end-to-end confidentiality through the *real* producer path.
 *
 * Nothing here builds a safe [ModelAttemptObservation] by hand. A governed operation really runs, the
 * provider really is invoked (and really fails once), the observer really records, and the timeline and
 * the reconstruction really read what was stored. Each canary enters at a distinct origin, and ingress
 * is proven by capturing the exact `ModelRequest` the provider received and the trace events the fixture
 * recorded — so no assertion below can pass because nothing happened.
 *
 * Boundary proven, and nothing wider: `provider -> engine -> AttemptRecordingObserver ->
 * AttemptObservationStore -> SemanticTimelineQuery -> EvidenceBackedReconstructor -> public rendering`.
 * Application logging, third-party collaborators and the JDBC column layout are outside this test (the
 * JDBC store has its own proof in `JdbcAttemptObservationConfidentialityTest`).
 */
class ProducerPathConfidentialityTest {
    private val store = InMemoryAttemptObservationStore()

    @Test
    fun `prompt, response and provider failure messages never reach recorded evidence`() {
        val promptCanary = "CANARY-PROMPT-4a91"
        val responseCanary = "CANARY-RESPONSE-7c3e"
        val failureCanary = "CANARY-FAILURE-9b02"
        val requests = mutableListOf<String>()

        val answer =
            runGoverned(RUN_CONTENT, promptCanary) {
                providerFailures = 1
                providerResponseContent = responseCanary
                providerFailureMessage = "$failureCanary: upstream said no"
                onRequest = { request -> requests += request.messages.joinToString { it.content } }
            }

        // --- non-vacuity. The operation ran, the provider was called twice, the canary left the app.
        assertThat(answer).isEqualTo(responseCanary)
        assertThat(requests).hasSize(2)
        assertThat(requests.first()).contains(promptCanary)
        assertThat(fixtureTraceTypes()).contains("provider.execute")

        val recorded = store.all()
        assertThat(recorded).hasSize(2)
        assertThat(recorded.map { it.outcome })
            .containsExactly(AttemptOutcome.FAILED, AttemptOutcome.SUCCEEDED)

        // --- allowed metadata survives: identity, model, timing, outcome, and the run it belongs to.
        val succeeded = recorded.last()
        assertThat(succeeded.providerId).isEqualTo("primary")
        assertThat(succeeded.requestedModel).isEqualTo("logical-model")
        assertThat(succeeded.reportedModel).isEqualTo("logical-model")
        assertThat(succeeded.workflowRunId).isEqualTo(RUN_CONTENT)
        assertThat(succeeded.attempt).isEqualTo(1)
        assertThat(succeeded.durationMillis).isGreaterThanOrEqualTo(0L)

        // --- the failure is categorised by class name only, never by the exception message.
        assertThat(recorded.first().failureCategory).isEqualTo("ProviderException")

        // --- no canary in any recorded field, nor in the record's rendering.
        assertNoCanary(recorded.toString(), promptCanary, responseCanary, failureCanary)

        // --- nor in the timeline, nor in the reconstruction, nor in their renderings.
        val timeline = query().timeline(TimelineRequest(RUN_CONTENT))
        assertThat(timeline.attempts()).hasSize(2)
        val reconstructed = reconstructor().reconstruct(RUN_CONTENT, null)
        val rendered =
            timeline.toString() + reconstructed.toString() + reconstructed.timeline.toString()

        assertNoCanary(rendered, promptCanary, responseCanary, failureCanary)
        // The rendering carries real facts, so the absence above is meaningful rather than vacuous.
        assertThat(rendered).contains("logical-model").contains(RUN_CONTENT)
    }

    @Test
    fun `tool arguments and tool results never reach recorded evidence`() {
        val promptCanary = "CANARY-TOOLPROMPT-1d77"
        val argumentsCanary = "CANARY-TOOLARGS-5e58"
        val resultCanary = "CANARY-TOOLRESULT-2f60"

        runGuardedToolCall {
            toolCallArgumentsJson = """{"card":"$argumentsCanary"}"""
            toolResultContent = "receipt=$resultCanary"
        }

        // --- non-vacuity: the tool really executed inside a real governed call.
        assertThat(fixtureTraceTypes())
            .contains("tool.execute.start")
            .contains("tool.execute.success")
        val recorded = store.all()
        assertThat(recorded).isNotEmpty()

        assertNoCanary(recorded.toString(), promptCanary, argumentsCanary, resultCanary)

        val timeline = query().timeline(TimelineRequest(RUN_TOOL))
        val rendered = timeline.toString() + reconstructor().reconstruct(RUN_TOOL, null).toString()
        assertNoCanary(rendered, promptCanary, argumentsCanary, resultCanary)
        assertThat(rendered).contains(RUN_TOOL)
    }

    // ---------------------------------------------------------------- fixtures

    private var fixture: ExecutionTraceFixture? = null

    private fun fixtureTraceTypes(): List<String> =
        fixture
            ?.trace
            ?.snapshot()
            ?.map { it.type }
            .orEmpty()

    private fun query() = SemanticTimelineQuery.of(store, governance = null)

    private fun reconstructor() =
        EvidenceBackedReconstructor(
            query(),
            HistoricalConfigurationSource { runId ->
                HistoricalConfigurationReference.Recorded(policyVersion = "policy-17-$runId")
            },
        )

    /** Runs one real governed retried call with [prompt] as the operation input. */
    private fun runGoverned(
        runId: String,
        prompt: String,
        configure: ExecutionTraceFixture.() -> Unit = {},
    ): String =
        runBlocking {
            val built =
                ExecutionTraceFixture().apply {
                    observerOverride = AttemptRecordingObserver(store)
                    configure()
                }
            fixture = built
            val service = built.engine().create(TraceService::class)
            withContext(GovernedRunScope(identity(runId))) { service.retryOnce(prompt) }
        }

    private fun runGuardedToolCall(configure: ExecutionTraceFixture.() -> Unit) {
        runBlocking {
            val built =
                ExecutionTraceFixture().apply {
                    observerOverride = AttemptRecordingObserver(store)
                    configure()
                }
            fixture = built
            val service = built.engine().create(TraceService::class)
            withContext(GovernedRunScope(identity(RUN_TOOL))) {
                service.toolCall(PROMPT_CANARY_TOOL)
            }
        }
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

    private fun assertNoCanary(
        rendered: String,
        vararg canaries: String,
    ) {
        canaries.forEach { canary ->
            assertThat(rendered)
                .describedAs("canary %s must not appear in recorded evidence", canary)
                .doesNotContain(canary)
        }
    }

    private companion object {
        const val RUN_CONTENT = "run-5g-content"
        const val RUN_TOOL = "run-5g-tool"
        const val PROMPT_CANARY_TOOL = "CANARY-TOOLPROMPT-1d77"
    }
}
