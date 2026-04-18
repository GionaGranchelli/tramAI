package io.aurora.core.model

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
    val timeout: Duration,
    val attributes: Map<String, Any> = emptyMap()
)

/**
 * User-facing tool contract.
 */
interface AuroraTool<I : Any, O : Any> {
    /** Unique tool name. */
    val name: String
    /** Description injected into the model's tool schema. */
    val description: String
    /** Input type used for schema generation and deserialization. */
    val inputType: KClass<I>
    /** Whether the tool is safe to retry on transient failure. */
    val idempotent: Boolean get() = false
    /** Degree of side effects produced by the tool. */
    val sideEffectLevel: SideEffectLevel get() = SideEffectLevel.UNKNOWN

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

    suspend fun execute(
        input: Any,
        context: ToolExecutionContext
    ): ToolResult
}
