package dev.tramai.core.model

import dev.tramai.core.policy.ToolSecurityMetadata
import java.time.Duration
import kotlin.reflect.KClass

/**
 * Common tool-side effect classification.
 */
enum class SideEffectLevel { NONE, READ_ONLY, WRITE, UNKNOWN }

/**
 * Contextual metadata passed to every tool execution.
 */
data class ToolExecutionContext(
    val operationName: String,
    val modelName: String,
    val attemptNumber: Int,
    val conversationId: String? = null,
    /**
     * Derived stable idempotency key for approval-gated tool executions.
     * Set only when the tool was suspended for approval and is being resumed.
     * Null for normal (non-approved) tool calls. When present, downstream
     * systems SHOULD use this key for deduplication.
     */
    val idempotencyKey: String? = null,
    val timeout: Duration,
    val attributes: Map<String, Any> = emptyMap()
)

/**
 * User-facing tool contract.
 */
interface TramaiTool<I : Any, O : Any> {
    /** Unique tool name. */
    val name: String
    /** Description injected into the model's tool schema. */
    val description: String
    /** Input type used for schema generation and deserialization. */
    val inputType: KClass<I>
    /** Whether repeating execution of this tool with the same logical input is safe. Repetition safety, not failure retryability: a non-idempotent tool may still fail transiently, but its side effects must not be repeated blindly. */
    val idempotent: Boolean get() = false
    /** Degree of side effects produced by the tool. */
    val sideEffectLevel: SideEffectLevel get() = SideEffectLevel.UNKNOWN
    /** Security metadata evaluated by the policy engine at BEFORE_TOOL_EXECUTION. */
    val security: ToolSecurityMetadata? get() = null

    /** Executes the tool with the given structured input. */
    suspend fun execute(input: I, context: ToolExecutionContext): O
}

/**
 * Engine-facing tool contract.
 */
interface ResolvedTool {
    val name: String
    val description: String
    val inputSchemaJson: String
    val idempotent: Boolean
    val sideEffectLevel: SideEffectLevel
    val security: ToolSecurityMetadata? get() = null

    suspend fun execute(
        input: Any,
        context: ToolExecutionContext
    ): ToolResult
}
