package dev.tramai.security.audit

import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.security.DlpContentLocation
import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpRedaction
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AuditEngineDlpRedactionAuditEmitterTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-06-04T12:00:00Z"), ZoneId.of("UTC"))

    private fun emitter(store: InMemoryAuditStore = InMemoryAuditStore()): Pair<AuditEngineDlpRedactionAuditEmitter, InMemoryAuditStore> =
        AuditEngineDlpRedactionAuditEmitter(AuditEngine(store, clock = fixedClock)) to store

    private fun modelOutputContext() = DlpContext(
        contentType = DlpContentType.MODEL_OUTPUT,
        contentLocation = DlpContentLocation.MODEL_RESPONSE_CONTENT,
        operationInterface = "dev.tramai.test.InvoiceService",
        operationMethod = "analyze",
        providerId = "ollama",
        modelName = "mistral",
        correlationId = "corr-1",
        dataClassification = DataClassification.CONFIDENTIAL,
        classificationSource = ClassificationSource.DECLARED,
    )

    private fun toolResultContext(contentLocation: DlpContentLocation = DlpContentLocation.TOOL_MESSAGE_CONTENT) = DlpContext(
        contentType = DlpContentType.TOOL_RESULT,
        contentLocation = contentLocation,
        operationInterface = "dev.tramai.test.InvoiceService",
        operationMethod = "analyze",
        toolName = "lookup",
        correlationId = "corr-1",
        dataClassification = DataClassification.RESTRICTED,
        classificationSource = ClassificationSource.RULE_BASED,
    )

    @Test
    fun `MODEL_OUTPUT maps to DLP_MODEL_OUTPUT with redacted decision and reason`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(modelOutputContext(), listOf(DlpRedaction("email", 1)))

        val event = store.readStream("corr-1").single()
        assertThat(event.enforcementPoint).isEqualTo("DLP_MODEL_OUTPUT")
        assertThat(event.decision).isEqualTo("REDACTED")
        assertThat(event.reasonCode).isEqualTo("dlp_redaction_applied")
    }

    @Test
    fun `TOOL_RESULT maps to DLP_TOOL_RESULT`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(toolResultContext(), listOf(DlpRedaction("email", 1)))

        assertThat(store.readStream("corr-1").single().enforcementPoint)
            .isEqualTo("DLP_TOOL_RESULT")
    }

    @Test
    fun `workflowRunId determines stream and is persisted`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(
            modelOutputContext().copy(
                workflowRunId = "run-123",
                correlationId = "corr-fallback",
            ),
            listOf(DlpRedaction("email", 1)),
        )

        val events = store.readStream("run-123")
        assertThat(events).hasSize(1)
        assertThat(events.single().workflowRunId).isEqualTo("run-123")
        assertThat(events.single().correlationId).isEqualTo("corr-fallback")
    }

    @Test
    fun `oversized workflowRunId fails closed`() {
        val (emitter, _) = emitter()
        assertThatThrownBy {
            runTest {
                emitter.emit(
                    modelOutputContext().copy(workflowRunId = "w".repeat(257)),
                    listOf(DlpRedaction("email", 1)),
                )
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("workflowRunId exceeds maximum length of 256")
    }

    @Test
    fun `oversized correlationId with valid workflowRunId fails closed`() {
        val (emitter, _) = emitter()
        assertThatThrownBy {
            runTest {
                emitter.emit(
                    modelOutputContext().copy(
                        correlationId = "c".repeat(257),
                        workflowRunId = "run-123",
                    ),
                    listOf(DlpRedaction("email", 1)),
                )
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("DLP audit correlation ID exceeds maximum length of 256")
    }

    @Test
    fun `padded correlationId is normalized and accepted`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(
            modelOutputContext().copy(correlationId = "  corr-1  "),
            listOf(DlpRedaction("email", 1)),
        )

        val event = store.readStream("corr-1").single()
        assertThat(event.correlationId).isEqualTo("corr-1")
    }

    @Test
    fun `padded workflowRunId is normalized and accepted`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(
            modelOutputContext().copy(
                correlationId = "  corr-fallback  ",
                workflowRunId = "  run-123  ",
            ),
            listOf(DlpRedaction("email", 1)),
        )

        val event = store.readStream("run-123").single()
        assertThat(event.workflowRunId).isEqualTo("run-123")
        assertThat(event.correlationId).isEqualTo("corr-fallback")
    }

    @Test
    fun `rule ID is persisted safely`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(modelOutputContext(), listOf(DlpRedaction("email.rule-1", 1)))

        assertThat(store.readStream("corr-1").single().metadata["ruleId"]).isEqualTo("email.rule-1")
    }

    @Test
    fun `replacementCount is persisted`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(modelOutputContext(), listOf(DlpRedaction("email", 3)))

        assertThat(store.readStream("corr-1").single().metadata["replacementCount"]).isEqualTo("3")
    }

    @Test
    fun `provider and model metadata are included`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(modelOutputContext(), listOf(DlpRedaction("email", 1)))

        val metadata = store.readStream("corr-1").single().metadata
        assertThat(metadata["providerName"]).isEqualTo("ollama")
        assertThat(metadata["modelName"]).isEqualTo("mistral")
        assertThat(metadata["contentLocation"]).isEqualTo("MODEL_RESPONSE_CONTENT")
    }

    @Test
    fun `tool name is included for tool results`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(toolResultContext(), listOf(DlpRedaction("email", 1)))

        val metadata = store.readStream("corr-1").single().metadata
        assertThat(metadata["toolName"]).isEqualTo("lookup")
        assertThat(metadata["contentLocation"]).isEqualTo("TOOL_MESSAGE_CONTENT")
    }

    @Test
    fun `classification metadata is included`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(toolResultContext(DlpContentLocation.TOOL_MESSAGE_TEXT_RUN), listOf(DlpRedaction("email", 1)))

        val metadata = store.readStream("corr-1").single().metadata
        assertThat(metadata["classification"]).isEqualTo("RESTRICTED")
        assertThat(metadata["classificationSource"]).isEqualTo("RULE_BASED")
        assertThat(metadata["contentLocation"]).isEqualTo("TOOL_MESSAGE_TEXT_RUN")
    }

    @Test
    fun `raw matched text is not persisted`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(modelOutputContext(), listOf(DlpRedaction("email", 1)))

        assertThat(store.readStream("corr-1").single().metadata.values)
            .noneMatch { it.contains("alice@example.com") }
    }

    @Test
    fun `sanitized output text is not persisted`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(modelOutputContext(), listOf(DlpRedaction("email", 1)))

        assertThat(store.readStream("corr-1").single().metadata.values)
            .noneMatch { it.contains("[REDACTED]") }
    }

    @Test
    fun `replacement string is not persisted`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(modelOutputContext(), listOf(DlpRedaction("email", 1)))

        assertThat(store.readStream("corr-1").single().metadata).doesNotContainKey("replacement")
    }

    @Test
    fun `regex pattern is not persisted`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(modelOutputContext(), listOf(DlpRedaction("email", 1)))

        assertThat(store.readStream("corr-1").single().metadata).doesNotContainKey("pattern")
    }

    @Test
    fun `multiple rules emit deterministic ordered events`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(
            modelOutputContext(),
            listOf(
                DlpRedaction("z-last", 1),
                DlpRedaction("a-first", 2),
            ),
        )

        val events = store.readStream("corr-1")
        assertThat(events).hasSize(2)
        assertThat(events.map { it.metadata["ruleId"] }).containsExactly("a-first", "z-last")
        assertThat(events.map { it.sequenceNumber }).containsExactly(1L, 2L)
    }

    @Test
    fun `duplicate rule IDs are grouped`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(
            modelOutputContext(),
            listOf(
                DlpRedaction("email", 1),
                DlpRedaction("email", 2),
            ),
        )

        val events = store.readStream("corr-1")
        assertThat(events).hasSize(1)
        assertThat(events.single().metadata["replacementCount"]).isEqualTo("3")
    }

    @Test
    fun `invalid rule ID fails closed`() {
        val (emitter, _) = emitter()
        assertThatThrownBy {
            runTest {
                emitter.emit(modelOutputContext(), listOf(DlpRedaction("Email", 1)))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("DLP rule ID is invalid")
    }

    @Test
    fun `replacementCount less than or equal to zero fails closed`() {
        val (emitter, _) = emitter()
        assertThatThrownBy {
            runTest {
                emitter.emit(modelOutputContext(), listOf(DlpRedaction("email", 0)))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("greater than zero")
    }

    @Test
    fun `negative replacementCount fails closed`() {
        val (emitter, _) = emitter()
        assertThatThrownBy {
            runTest {
                emitter.emit(modelOutputContext(), listOf(DlpRedaction("email", -1)))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("greater than zero")
    }

    @Test
    fun `blank correlation ID fails closed`() {
        val (emitter, _) = emitter()
        assertThatThrownBy {
            runTest {
                emitter.emit(modelOutputContext().copy(correlationId = " "), listOf(DlpRedaction("email", 1)))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not be blank")
    }

    @Test
    fun `oversized correlation ID fails closed`() {
        val (emitter, _) = emitter()
        assertThatThrownBy {
            runTest {
                emitter.emit(modelOutputContext().copy(correlationId = "c".repeat(257)), listOf(DlpRedaction("email", 1)))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maximum length of 256")
    }

    @Test
    fun `resulting event chain passes AuditChainVerifier`() = runTest {
        val (emitter, store) = emitter()
        emitter.emit(
            toolResultContext(),
            listOf(
                DlpRedaction("email", 1),
                DlpRedaction("ssn", 2),
            ),
        )

        assertThat(AuditChainVerifier.verify(store.readStream("corr-1")).isValid).isTrue()
    }
}
