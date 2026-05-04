package dev.tramai.platform

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.server.WorkflowRegistration
import dev.tramai.server.WorkflowRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.time.Clock
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class PlatformConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun workflowRegistry(
        registrations: List<WorkflowRegistration>,
        dataSource: ObjectProvider<DataSource>,
    ): WorkflowRegistry {
        val registry = WorkflowRegistry(dataSource.ifAvailable)
        registrations.forEach { it.register(registry) }
        return registry
    }

    @Bean
    @ConditionalOnMissingBean
    fun workflowExecutionScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun teamRepository(dataSource: DataSource): TeamRepository = TeamRepository(dataSource)

    @Bean
    fun projectRepository(dataSource: DataSource): ProjectRepository = ProjectRepository(dataSource)

    @Bean
    fun apiKeyRepository(dataSource: DataSource): ApiKeyRepository = ApiKeyRepository(dataSource)

    @Bean
    fun auditLogRepository(
        dataSource: DataSource,
        objectMapper: ObjectMapper,
    ): AuditLogRepository = AuditLogRepository(dataSource, objectMapper)

    @Bean
    fun pluginStateRepository(dataSource: DataSource): PluginStateRepository = PluginStateRepository(dataSource)

    @Bean
    fun auditLogService(
        repository: AuditLogRepository,
        clock: Clock,
    ): AuditLogService = AuditLogService(repository, clock)

    @Bean
    fun apiKeyService(
        teamRepository: TeamRepository,
        projectRepository: ProjectRepository,
        repository: ApiKeyRepository,
        auditLogService: AuditLogService,
        clock: Clock,
    ): ApiKeyService = ApiKeyService(
        teamRepository = teamRepository,
        projectRepository = projectRepository,
        repository = repository,
        auditLogService = auditLogService,
        clock = clock,
    )

    @Bean
    fun apiKeyAuthenticator(
        repository: ApiKeyRepository,
        clock: Clock,
    ): ApiKeyAuthenticator = ApiKeyAuthenticator(repository, clock)

    @Bean
    fun rateLimiter(clock: Clock): ApiKeyRateLimiter = ApiKeyRateLimiter(clock)

    @Bean
    fun teamProjectRegistry(
        teamRepository: TeamRepository,
        projectRepository: ProjectRepository,
    ): TeamProjectRegistry = TeamProjectRegistry(teamRepository, projectRepository)

    @Bean
    fun tenantWorkflowRunStores(): TenantWorkflowRunStores = TenantWorkflowRunStores()

    @Bean
    fun webhookAdapterRegistry(): WebhookAdapterRegistry = WebhookAdapterRegistry()

    @Bean
    fun pluginManager(
        pluginStateRepository: PluginStateRepository,
        webhookAdapterRegistry: WebhookAdapterRegistry,
        @Value("\${tramai.platform.plugins.dir:\${java.io.tmpdir}/tramai-platform-plugins}") pluginDirectory: String,
    ): PluginManager = PluginManager(
        pluginDirectory = Path.of(pluginDirectory),
        pluginStateRepository = pluginStateRepository,
        webhookAdapterRegistry = webhookAdapterRegistry,
    )

    @Bean
    fun pluginDiscoveryRunner(pluginManager: PluginManager): ApplicationRunner = ApplicationRunner {
        pluginManager.refresh()
    }

    @Bean
    fun workflowService(
        registry: WorkflowRegistry,
        runStores: TenantWorkflowRunStores,
        auditLogService: AuditLogService,
        workflowExecutionScope: CoroutineScope,
        objectMapper: ObjectMapper,
        teamProjectRegistry: TeamProjectRegistry,
        webhookAdapterRegistry: WebhookAdapterRegistry,
    ): PlatformWorkflowService = PlatformWorkflowService(
        registry = registry,
        runStores = runStores,
        auditLogService = auditLogService,
        workflowExecutionScope = workflowExecutionScope,
        objectMapper = objectMapper,
        teamProjectRegistry = teamProjectRegistry,
        webhookAdapterRegistry = webhookAdapterRegistry,
    )
}
