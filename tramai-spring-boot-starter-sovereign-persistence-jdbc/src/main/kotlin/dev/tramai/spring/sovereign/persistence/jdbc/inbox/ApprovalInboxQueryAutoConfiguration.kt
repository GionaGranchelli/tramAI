package dev.tramai.spring.sovereign.persistence.jdbc.inbox

import dev.tramai.spring.sovereign.persistence.jdbc.SovereignJdbcPersistenceAutoConfiguration
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQueryService
import java.time.Clock
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for the Preview JDBC-backed approval inbox query service.
 *
 * Runs after [SovereignJdbcPersistenceAutoConfiguration] so that JDBC store
 * tables are guaranteed to exist. Only activates when:
 * - `tramai.sovereign.persistence.type=jdbc` is configured
 * - A [DataSource] bean is available
 * - No custom [ApprovalInboxQueryService] bean exists
 */
@AutoConfiguration(
    after = [SovereignJdbcPersistenceAutoConfiguration::class],
)
@ConditionalOnProperty(
    prefix = "tramai.sovereign.persistence",
    name = ["type"],
    havingValue = "jdbc",
)
@ConditionalOnBean(DataSource::class)
class ApprovalInboxQueryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApprovalInboxQueryService::class)
    fun jdbcApprovalInboxQueryService(
        dataSource: DataSource,
        clockProvider: ObjectProvider<Clock>,
    ): ApprovalInboxQueryService =
        JdbcApprovalInboxQueryService(
            dataSource = dataSource,
            clock = clockProvider.ifAvailable ?: Clock.systemUTC(),
        )
}
