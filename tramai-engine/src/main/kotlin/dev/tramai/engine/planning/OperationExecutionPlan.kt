package dev.tramai.engine.planning

import dev.tramai.engine.OperationDefinition

/** Immutable metadata compiled for one service operation before invocation. */
internal data class OperationExecutionPlan(
    val definition: OperationDefinition,
    val fingerprint: String,
    val serviceInterface: String,
    val methodName: String,
)
