@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine.observation

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
import dev.tramai.core.observation.attempt.AttemptProvenance
import dev.tramai.core.observation.attempt.AttemptRecordingObserver
import dev.tramai.core.observation.attempt.InMemoryAttemptObservationStore
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.engine.characterization.ExecutionTraceFixture
import dev.tramai.engine.characterization.TraceService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Gate 4 of Epic 0.7.5g — accounting and provenance integrity on the **real recording path**.
 *
 * `TimelineEvidenceIntegrityTest` proves what the *read* path does with given evidence; this proves what
 * the recorder actually writes when a provider really answers. The distinction matters: a mutant in the
 * recorder is invisible to a test that builds its observations by hand, which is exactly how the first
 * mutation campaign survived four mutants. Every assertion below comes from a real governed call.
 */
class ProducerPathProvenanceTest {
    private val store = InMemoryAttemptObservationStore()

    @Test
    fun `usage and provenance are recorded exactly as the provider reported them`() {
        runGoverned(RUN_USAGE) {
            providerInputTokens = 12
            providerOutputTokens = 5
            providerThinkingTokens = 400
        }

        val recorded = store.all().single()
        assertThat(recorded.inputTokens).isEqualTo(12)
        // Reasoning tokens are never folded into the completion count.
        assertThat(recorded.outputTokens).isEqualTo(5)
        assertThat(recorded.thinkingTokens).isEqualTo(400)
        // A default STOP is not a provider report: nothing on this path can claim one.
        assertThat(recorded.finishReasonReported).isFalse()
        assertThat(recorded.finishReason).isEqualTo("STOP")
        // Attribution, never authority.
        assertThat(recorded.provenance).isEqualTo(AttemptProvenance.OBSERVED_IN_PROCESS)
    }

    @Test
    fun `an unreported provider model stays unknown instead of borrowing the requested one`() {
        runGoverned(RUN_UNREPORTED) { providerReportsModel = false }

        val recorded = store.all().single()
        assertThat(recorded.requestedModel).isEqualTo("logical-model")
        assertThat(recorded.reportedModel).isNull()
    }

    @Test
    fun `a provider that reports no usage leaves every component unknown`() {
        runGoverned(RUN_NO_USAGE) {}

        val recorded = store.all().single()
        assertThat(recorded.inputTokens).isNull()
        assertThat(recorded.outputTokens).isNull()
        assertThat(recorded.thinkingTokens).isNull()
    }

    // ---------------------------------------------------------------- fixture

    private fun runGoverned(
        runId: String,
        configure: ExecutionTraceFixture.() -> Unit,
    ) {
        runBlocking {
            val fixture =
                ExecutionTraceFixture().apply {
                    observerOverride = AttemptRecordingObserver(store)
                    configure()
                }
            val service = fixture.engine().create(TraceService::class)
            withContext(GovernedRunScope(identity(runId))) { service.answer("a request sentinel") }
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

    private companion object {
        const val RUN_USAGE = "run-5g-usage"
        const val RUN_UNREPORTED = "run-5g-unreported"
        const val RUN_NO_USAGE = "run-5g-no-usage"
    }
}
