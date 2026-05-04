package dev.tramai.platform

import dev.tramai.orchestration.ExternalStepExecutor
import dev.tramai.orchestration.ExternalStepExecutorFactory

class EchoStepExecutorFactory : ExternalStepExecutorFactory {
    override val typeId: String = "demo.echo"

    override fun create(): ExternalStepExecutor = ExternalStepExecutor { spec ->
        mapOf(
            "pluginMessage" to spec["message"],
            "executed" to true,
        )
    }
}

class DemoWebhookAdapterFactory : WebhookAdapterFactory {
    override val sourceId: String = "demo.webhook"

    override fun create(): WebhookAdapter = WebhookAdapter { payload, _ ->
        val match = """"message"\s*:\s*"([^"]+)"""".toRegex().find(payload)
        mapOf(
            "message" to (match?.groupValues?.getOrNull(1) ?: "unknown"),
            "source" to sourceId,
            "processed" to true,
        )
    }
}

class TestPlatformPlugin : TramaiPlugin {
    override val id: String = "demo-plugin"
    override val version: String = "1.0.0"

    override fun stepExecutors(): List<ExternalStepExecutorFactory> = listOf(EchoStepExecutorFactory())

    override fun webhookAdapters(): List<WebhookAdapterFactory> = listOf(DemoWebhookAdapterFactory())

    override fun dashboardExtensions(): List<DashboardExtension> = listOf(
        DashboardExtension(
            id = "demo-dashboard",
            title = "Demo Dashboard",
        ),
    )
}
