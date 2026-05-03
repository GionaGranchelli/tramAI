package dev.tramai.server

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

fun interface WorkflowRegistration {
    fun register(registry: WorkflowRegistry)
}

@Configuration(proxyBeanMethods = false)
class ServerConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun workflowRegistry(
        registrations: List<WorkflowRegistration>,
        dataSource: ObjectProvider<javax.sql.DataSource>,
    ): WorkflowRegistry {
        val registry = WorkflowRegistry(dataSource.ifAvailable)
        registrations.forEach { it.register(registry) }
        return registry
    }

    @Bean
    @ConditionalOnMissingBean
    fun workflowRunStore(): WorkflowRunStore = WorkflowRunStore()
}
