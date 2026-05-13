package dev.tramai.orchestration

import dev.tramai.core.security.StepSecurityConfig

internal object SecurityEvents {
    const val STEP_EXECUTED = "tramai.workflow.security.step_executed"
    const val SANITIZER_TRIGGERED = "tramai.workflow.security.sanitizer_triggered"
    const val COMMAND_DENIED = "tramai.workflow.security.command_denied"
    const val OUTPUT_REJECTED = "tramai.workflow.security.output_rejected"
}

internal fun StepSecurityConfig.defenseMode(): String = when (this) {
    is StepSecurityConfig.Default -> "default"
    is StepSecurityConfig.Custom -> "custom"
    is StepSecurityConfig.Disabled -> "disabled"
}
