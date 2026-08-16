package dev.tramai.engine.tool

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.ToolFailureDiagnosticEvent
import dev.tramai.core.observation.ToolFailureDiagnosticObserver
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.provider.componentOperation
import java.util.concurrent.atomic.AtomicBoolean

internal fun testTool(name: String = "test-tool", idempotent: Boolean = false, execute: suspend (String, ToolExecutionContext) -> ToolResult = { _, _ -> ToolResult.Success("ok") }): ResolvedTool = object : ResolvedTool {
    override val name = name
    override val description = "test tool $name"
    override val inputSchemaJson = "{}"
    override val idempotent = idempotent
    override val sideEffectLevel = SideEffectLevel.NONE
    override suspend fun execute(input: Any, context: ToolExecutionContext) = execute(input.toString(), context)
}

internal fun policyHelper(decide: suspend (dev.tramai.core.policy.PolicyContext) -> PolicyDecision = { PolicyDecision.Allow }) =
    PolicyEnforcementHelper(PolicyEngine { decide(it) }, AtomicBoolean())

internal fun toolOperation() = componentOperation().copy(toolDefinitions = emptyList())
internal fun toolOperation(vararg names: String): OperationDefinition = toolOperation().copy(toolDefinitions = names.map { dev.tramai.core.model.ToolDefinition(it, it, "{}") })
internal fun toolRequest(tool: ResolvedTool, operation: OperationDefinition = toolOperation(), messages: List<dev.tramai.core.model.Message> = emptyList()) = ToolExecutionRequest(tool, ToolCall("call-1", tool.name, "{}"), operation, "cid", ExecutionSecurityContext(), EngineExecutionIdentity("run", "cid", Sha256Digest.of("sha256:${"a".repeat(64)}"), "v1", "actor"), messages)
internal class RecordingToolObserver(private val failure: Throwable? = null) : ToolFailureDiagnosticObserver {
    val events = mutableListOf<ToolFailureDiagnosticEvent>()
    override fun record(event: ToolFailureDiagnosticEvent) { events += event; failure?.let { throw it } }
}
