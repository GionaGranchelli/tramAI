package dev.tramai.observability

import dev.tramai.orchestration.ShellCommand
import dev.tramai.orchestration.ShellCommandDefinition
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.workflow
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.AfterTest
import kotlin.test.Test

class WorkflowStepFailureTelemetryTest {
    private val exporter = InMemorySpanExporter.create()
    private val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
    private val telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()

    @AfterTest
    fun tearDown() {
        provider.shutdown()
    }

    @Test
    fun `workflow failure telemetry excludes external failure detail`() {
        val secret = "token=top-secret url=https://user:pass@example.test/?q=secret /tmp/private.sql stderr=secret"
        val workflow = workflow<String>("safe-failure") {
            shellStep(
                name = "shell",
                definition = ShellCommandDefinition("echo"),
                command = { _, _ -> throw IllegalStateException(secret) },
                merge = { state, _: dev.tramai.orchestration.ShellResult, _ -> state },
            )
        }.build { it }
        assertThatThrownBy {
            runBlocking {
                workflow.run("state", WorkflowContext("telemetry"), OpenTelemetryWorkflowObserver(telemetry))
            }
        }
        val span = exporter.finishedSpanItems.single()
        val rendered = buildString {
            append(span.attributes.asMap())
            append(span.status.description)
            append(span.events)
        }
        org.assertj.core.api.Assertions.assertThat(rendered).doesNotContain(secret)
    }
}
