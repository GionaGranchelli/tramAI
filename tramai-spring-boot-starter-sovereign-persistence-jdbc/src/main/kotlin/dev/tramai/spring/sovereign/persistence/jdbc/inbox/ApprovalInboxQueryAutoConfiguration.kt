package dev.tramai.spring.sovereign.persistence.jdbc.inbox

import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQueryService
import java.time.Clock
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for the Preview JDBC-backed approval inbox query service.
 *
 * Activates when a [DataSource] is available and no custom
 * [ApprovalInboxQueryService] bean exists.
 */
@AutoConfiguration
@ConditionalOnBean(DataSource::class)
class ApprovalInboxQueryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApprovalInboxQueryService::class)
    fun jdbcApprovalInboxQueryService(
        dataSource: DataSource,
        clock: Clock,
    ): ApprovalInboxQueryService =
        JdbcApprovalInboxQueryService(dataSource, clock)
}
