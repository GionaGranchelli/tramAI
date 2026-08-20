package dev.tramai.engine.tool

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.security.*
import dev.tramai.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ToolResultSanitizerTest {
    private fun sanitizer(dlp: DlpInterceptor = NoOpDlpInterceptor, audit: DlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter, limit: Long = 100_000, events: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf()) = ToolResultSanitizer(ToolRegistry(mapOf("tool" to testTool("tool"))), dlp, audit, ToolResultFilteringSettings(limit), object : EngineEventObserver { override fun onEngineEvent(name: String, attributes: Map<String, Any?>) { events += name to attributes } })
    private suspend fun ToolResultSanitizer.sanitize(message: Message) = sanitize(message, toolOperation(), "tool", "cid", ExecutionSecurityContext())
    @Test fun `plain success formats tool message`() { val message = sanitizer().format(ToolResult.Success("hello"), "call"); assertThat(message).isEqualTo(Message(MessageRole.TOOL, "hello", toolCallId = "call")) }
    @Test fun `content parts prepend value as text`() { val message = sanitizer().format(ToolResult.Success("hello", listOf(ContentPart.TextPart("extra"))), "call"); assertThat(message.content).isEmpty(); assertThat(message.contentParts).containsExactly(ContentPart.TextPart("hello"), ContentPart.TextPart("extra")) }
    @Test fun `safe failures have stable formatting`() { val s = sanitizer(); assertThat(s.format(ToolResult.InvalidInput("bad"), "c").content).isEqualTo("Error: bad"); assertThat(s.format(ToolResult.PermanentFailure("bad"), "c").content).isEqualTo("Permanent error: bad") }
    @Test fun `aggregate limit rejects and emits event`() { val events = mutableListOf<Pair<String, Map<String, Any?>>>(); val s = sanitizer(object : DlpInterceptor { override fun inspect(context: DlpContext, text: String) = DlpResult(text) }, limit = 5, events = events); assertThatThrownBy { runBlocking { s.sanitize(Message(MessageRole.TOOL, "abcdef")) } }.hasMessageContaining("exceeds aggregate input limit"); assertThat(events.single().first).isEqualTo(RuntimeEvents.DLP_TOOL_RESULT_REJECTED.name); assertThat(events.single().second).containsEntry("reasonCode", "aggregate_text_limit_exceeded") }
    @Test fun `sanitized limit rejects growth`() { val s = sanitizer(object : DlpInterceptor { override fun inspect(context: DlpContext, text: String) = DlpResult(text + "xxxxx") }, limit = 5); assertThatThrownBy { runBlocking { s.sanitize(Message(MessageRole.TOOL, "a")) } }.hasMessageContaining("Sanitized tool result") }
    @Test fun `audit failure is terminal`() { val s = sanitizer(object : DlpInterceptor { override fun inspect(context: DlpContext, text: String) = DlpResult("x", listOf(DlpRedaction("r", 1))) }, object : DlpRedactionAuditEmitter { override suspend fun emit(context: DlpContext, redactions: List<DlpRedaction>) { throw RuntimeException() } }); assertThatThrownBy { runBlocking { s.sanitize(Message(MessageRole.TOOL, "secret")) } }.hasMessageContaining("DLP redaction audit emission failed") }
    @Test fun `authoritative evidence is required`() { val s = sanitizer(object : DlpInterceptor { override fun inspect(context: DlpContext, text: String) = DlpResult("changed") }, object : DlpRedactionAuditEmitter { override suspend fun emit(context: DlpContext, redactions: List<DlpRedaction>) = Unit }); assertThatThrownBy { runBlocking { s.sanitize(Message(MessageRole.TOOL, "original")) } }.hasMessageContaining("without redaction evidence") }
    @Test fun `cancellation from dlp propagates`() { val cancellation = CancellationException("stop"); val s = sanitizer(object : DlpInterceptor { override fun inspect(context: DlpContext, text: String): DlpResult = throw cancellation }); assertThatThrownBy { runBlocking { s.sanitize(Message(MessageRole.TOOL, "x")) } }.isSameAs(cancellation) }
}
