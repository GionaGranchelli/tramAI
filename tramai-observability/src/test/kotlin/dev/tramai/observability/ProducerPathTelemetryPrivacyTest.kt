package dev.tramai.observability

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.attempt.AttemptRecordingObserver
import dev.tramai.core.observation.attempt.InMemoryAttemptObservationStore
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.create
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gate 1 of Epic 0.7.5g — telemetry and attempt recording share one span-owning observer, so this test
 * proves the confidentiality of *both* qualified surfaces from a single real governed execution:
 *
 * `provider -> engine -> AttemptRecordingObserver(delegate = OpenTelemetryOperationObserver) -> OTel span
 * and PostgreSQL-ready attempt row`.
 *
 * Each canary enters at a distinct origin and is proven to have entered (the operation really returned
 * the response canary, and the provider really failed once), so no assertion here can pass vacuously.
 *
 * Boundary proven, and nothing wider: the attempt record and the OTel span produced by this
 * composition. Application logging, exporter back-ends and arbitrary third-party observers are outside
 * it.
 */
class ProducerPathTelemetryPrivacyTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracerProvider =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
    private val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()

    @AiService
    interface ConfidentialityProbe {
        @Operation(prompt = "Answer", model = "logical-model", providerRetries = 1)
        suspend fun ask(input: String): String
    }

    private class CanaryProvider(
        private val responseContent: String,
        private val failureMessage: String,
        private val failures: Int,
    ) : ModelProvider {
        private val calls = AtomicInteger()

        override suspend fun complete(request: ModelRequest): ModelResponse {
            if (calls.incrementAndGet() <= failures) {
                throw ProviderException(failureMessage, retryable = true)
            }
            return ModelResponse(
                content = responseContent,
                modelUsed = request.model,
                inputTokens = 12,
                outputTokens = 5,
            )
        }

        override fun providerId(): String = "primary"
    }

    @Test
    fun `provider exception messages and response content never reach a span or an attempt row`() {
        val store = InMemoryAttemptObservationStore()
        val responseCanary = "CANARY-RESPONSE-7c3e"
        val failureCanary = "CANARY-FAILURE-9b02"
        val promptCanary = "CANARY-PROMPT-4a91"
        val provider = CanaryProvider(responseCanary, "$failureCanary: upstream said no", failures = 1)
        val engine =
            TramaiEngine(
                provider = provider,
                // One observer, two qualified surfaces: the attempt row records first, then delegates.
                operationObserver =
                    AttemptRecordingObserver(
                        store,
                        delegate = OpenTelemetryOperationObserver(openTelemetry),
                    ),
            )

        val answer = runBlocking { engine.create<ConfidentialityProbe>().ask(promptCanary) }

        // --- non-vacuity: the call really ran, really failed once, and really returned the canary.
        assertThat(answer).isEqualTo(responseCanary)
        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)

        // --- allowed telemetry survives.
        val successSpan = spans.last()
        assertThat(
            successSpan.attributes
                .asMap()
                .keys
                .map { it.key },
        ).contains("gen_ai.system", "gen_ai.request.model", "gen_ai.response.model")

        // --- no canary anywhere in a span: attributes, events, or status description.
        val renderedSpans =
            spans.joinToString("\n") { span ->
                span.attributes.asMap().toString() +
                    span.events.joinToString { event -> event.name + event.attributes.asMap().toString() } +
                    span.status.toString()
            }
        assertNoCanary(renderedSpans, promptCanary, responseCanary, failureCanary)

        // --- the failure is exported as a type only: fixed status description, no message, no stack.
        assertThat(spans.first().status.description).isEqualTo("Provider call failed")
        val exceptionEvent = spans.first().events.single { it.name == "exception" }
        assertThat(
            exceptionEvent.attributes
                .asMap()
                .keys
                .map { it.key },
        ).containsExactlyInAnyOrder("exception.type", "exception.escaped")

        // --- and the attempt rows carry no canary either.
        val rows = store.all()
        assertThat(rows).hasSize(2)
        assertNoCanary(rows.toString(), promptCanary, responseCanary, failureCanary)
        assertThat(rows.last().reportedModel).isEqualTo("logical-model")

        tracerProvider.shutdown()
    }

    private fun assertNoCanary(
        rendered: String,
        vararg canaries: String,
    ) {
        canaries.forEach { canary ->
            assertThat(rendered)
                .describedAs("canary %s must not appear in telemetry or attempt evidence", canary)
                .doesNotContain(canary)
        }
    }
}
