package dev.tramai.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.orchestration.NoOpWorkflowObserver
import dev.tramai.scheduler.JdbcWorkflowSchedulerStore
import dev.tramai.scheduler.WorkflowSchedulerStore

fun interface WorkflowRegistration {
    fun register(registry: WorkflowRegistry)
}

data class WebhookConfiguration(
    val secret: String,
    val maxRequestBodyBytes: Long,
) {
    init {
        require(maxRequestBodyBytes > 0) { "maxRequestBodyBytes must be greater than zero" }
    }
}

@Configuration(proxyBeanMethods = false)
class ServerConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun workflowRegistry(
        registrations: List<WorkflowRegistration>,
        dataSource: ObjectProvider<javax.sql.DataSource>,
        schedulerStore: ObjectProvider<WorkflowSchedulerStore>,
    ): WorkflowRegistry {
        val registry = WorkflowRegistry(
            dataSource = dataSource.ifAvailable,
            schedulerStore = schedulerStore.ifAvailable,
        )
        registrations.forEach { it.register(registry) }
        return registry
    }

    @Bean
    @ConditionalOnMissingBean
    fun workflowRunStore(
        @Value("\${tramai.server.max-run-history-size:1000}") maxHistorySize: Int,
        @Value("\${tramai.server.sse-event-buffer-size:100}") sseEventBufferSize: Int,
    ): WorkflowRunStore = WorkflowRunStore(maxHistorySize = maxHistorySize, sseEventBufferSize = sseEventBufferSize)

    @Bean
    @ConditionalOnMissingBean
    fun workflowExecutionScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Bean
    @ConditionalOnMissingBean
    fun webhookConfiguration(
        @Value("\${tramai.server.webhooks.secret:}") secret: String,
        @Value("\${tramai.server.webhooks.max-request-body-bytes:\${tramai.server.max-request-body-bytes:1048576}}") maxRequestBodyBytes: Long,
    ): WebhookConfiguration = WebhookConfiguration(
        secret = secret,
        maxRequestBodyBytes = maxRequestBodyBytes,
    )

    @Bean
    @ConditionalOnMissingBean
    fun webhookSignatureVerifier(
        webhookConfiguration: WebhookConfiguration,
    ): WebhookSignatureVerifier = GitHubWebhookSignatureVerifier(secret = webhookConfiguration.secret)

    @Bean
    @ConditionalOnMissingBean
    fun requestBodySizeLimitFilter(
        @Value("\${tramai.server.max-request-body-bytes:1048576}") maxRequestBodyBytes: Long,
        webhookConfiguration: WebhookConfiguration,
    ): RequestBodySizeLimitFilter = RequestBodySizeLimitFilter(
        workflowMaxRequestBodyBytes = maxRequestBodyBytes,
        webhookMaxRequestBodyBytes = webhookConfiguration.maxRequestBodyBytes,
    )

    @Bean
    @ConditionalOnMissingBean
    fun workerRegistry(
        objectMapper: ObjectMapper,
    ): InMemoryWorkerRegistry = InMemoryWorkerRegistry(objectMapper = objectMapper)

    @Bean
    @ConditionalOnMissingBean
    fun auditLogStore(
        @Value("\${tramai.server.max-audit-entries:10000}") maxEntries: Int,
    ): InMemoryAuditLogStore = InMemoryAuditLogStore(maxEntries = maxEntries)

    @Bean
    @ConditionalOnBean(javax.sql.DataSource::class)
    @ConditionalOnMissingBean(WorkflowSchedulerStore::class)
    fun workflowSchedulerStore(
        dataSource: javax.sql.DataSource,
        scheduleEventObserver: ObjectProvider<ScheduleEventObserver>,
    ): WorkflowSchedulerStore = JdbcWorkflowSchedulerStore(
        dataSource = dataSource,
        observer = scheduleEventObserver.ifAvailable ?: NoOpWorkflowObserver,
    )

    @Bean
    @ConditionalOnMissingBean
    fun scheduleEventObserver(
        scheduleController: ObjectProvider<ScheduleController>,
    ): ScheduleEventObserver = ScheduleEventObserver(scheduleController)
}
