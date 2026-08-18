package dev.tramai.engine.invocation

import dev.tramai.engine.planning.OperationExecutionPlan

/**
 * Immutable execution context for one JVM invocation, resolved before dispatch.
 *
 * Carries the compiled [OperationExecutionPlan] so downstream execution never
 * reverse-looks-up fingerprints from the service definition.
 */
internal data class InvocationExecutionContext(
    val plan: OperationExecutionPlan,
    val arguments: List<Any?>,
    val conversationId: String?,
)
