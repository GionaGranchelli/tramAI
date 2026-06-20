package dev.tramai.spring.sovereign.ops.actuator

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "tramai.sovereign.ops.actuator.worker-status")
data class SovereignOpsWorkerStatusEndpointProperties(
    val enabled: Boolean = false,
)
