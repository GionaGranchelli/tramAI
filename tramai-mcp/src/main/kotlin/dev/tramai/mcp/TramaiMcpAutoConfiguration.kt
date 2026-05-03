package dev.tramai.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.server.WorkflowController
import dev.tramai.server.WorkflowRegistry
import dev.tramai.server.WorkflowRunStore
import dev.tramai.structured.JacksonStructuredOutputHandler
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnBean(
    value = [
        WorkflowRegistry::class,
        WorkflowRunStore::class,
        WorkflowController::class,
    ],
)
@EnableConfigurationProperties(TramaiMcpProperties::class)
class TramaiMcpAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun tramaiMcpStructuredOutputHandler(
        objectMapper: ObjectMapper,
    ): JacksonStructuredOutputHandler = JacksonStructuredOutputHandler(objectMapper)

    @Bean
    @ConditionalOnMissingBean
    fun mcpToolHandlers(
        workflowRegistry: WorkflowRegistry,
        workflowRunStore: WorkflowRunStore,
        workflowController: WorkflowController,
        objectMapper: ObjectMapper,
        structuredOutputHandler: JacksonStructuredOutputHandler,
    ): McpToolHandlers = McpToolHandlers(
        registry = workflowRegistry,
        runStore = workflowRunStore,
        workflowController = workflowController,
        objectMapper = objectMapper,
        structuredOutputHandler = structuredOutputHandler,
    )

    @Bean
    @ConditionalOnMissingBean
    fun tramaiMcpServer(
        handlers: McpToolHandlers,
    ): TramaiMcpServer = TramaiMcpServer(handlers)

    @Bean
    @ConditionalOnMissingBean
    fun tramaiMcpLifecycle(
        properties: TramaiMcpProperties,
        server: TramaiMcpServer,
    ): SmartLifecycle = TramaiMcpLifecycle(properties, server)
}

@ConfigurationProperties("tramai.mcp")
data class TramaiMcpProperties(
    val stdio: Stdio = Stdio(),
    val sse: Sse = Sse(),
) {
    data class Stdio(
        val enabled: Boolean = false,
    )

    data class Sse(
        val enabled: Boolean = false,
        val host: String = "127.0.0.1",
        val port: Int = 8091,
        val path: String = "/mcp",
    )
}

private class TramaiMcpLifecycle(
    private val properties: TramaiMcpProperties,
    private val server: TramaiMcpServer,
) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        if (running) {
            return
        }
        if (properties.stdio.enabled) {
            server.startStdio()
        }
        if (properties.sse.enabled) {
            server.startSse(
                host = properties.sse.host,
                port = properties.sse.port,
                path = properties.sse.path,
            )
        }
        running = properties.stdio.enabled || properties.sse.enabled
    }

    override fun stop() {
        server.close()
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true
}
