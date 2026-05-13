package dev.tramai.platform

import java.util.concurrent.ConcurrentHashMap

data class WebhookConfig(
    val name: String,
    val teamId: String,
    val projectId: String,
    val source: String,
)

data class RegisterWebhookConfigRequest(
    val name: String,
    val teamId: String,
    val projectId: String,
    val source: String,
)

class WebhookConfigurationNotFoundException(
    name: String,
) : RuntimeException("Webhook '$name' was not found")

class WebhookConfigService(
    private val teamProjectRegistry: TeamProjectRegistry,
) {
    private val configs = ConcurrentHashMap<String, WebhookConfig>()

    fun register(request: RegisterWebhookConfigRequest): WebhookConfig {
        require(request.name.isNotBlank()) { "Webhook name must not be blank" }
        require(request.source.isNotBlank()) { "Webhook source must not be blank" }
        teamProjectRegistry.requireProject(request.teamId, request.projectId)
        val config = WebhookConfig(
            name = request.name,
            teamId = request.teamId,
            projectId = request.projectId,
            source = request.source,
        )
        configs[request.name] = config
        return config
    }

    fun get(name: String): WebhookConfig =
        configs[name] ?: throw WebhookConfigurationNotFoundException(name)
}
