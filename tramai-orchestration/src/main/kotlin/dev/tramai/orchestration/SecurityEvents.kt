package dev.tramai.orchestration

import dev.tramai.core.observation.event.RuntimeEvents

internal object SecurityEvents {
    val STEP_EXECUTED: String = RuntimeEvents.WORKFLOW_SECURITY_STEP_EXECUTED.name
    val SANITIZER_TRIGGERED: String = RuntimeEvents.WORKFLOW_SECURITY_SANITIZER_TRIGGERED.name
    val COMMAND_DENIED: String = RuntimeEvents.WORKFLOW_SECURITY_COMMAND_DENIED.name
    val OUTPUT_REJECTED: String = RuntimeEvents.WORKFLOW_SECURITY_OUTPUT_REJECTED.name
}

internal fun dev.tramai.core.security.StepSecurityConfig.defenseMode(): String = when (this) {
    is dev.tramai.core.security.StepSecurityConfig.Default -> "default"
    is dev.tramai.core.security.StepSecurityConfig.Custom -> "custom"
    is dev.tramai.core.security.StepSecurityConfig.Disabled -> "disabled"
}
