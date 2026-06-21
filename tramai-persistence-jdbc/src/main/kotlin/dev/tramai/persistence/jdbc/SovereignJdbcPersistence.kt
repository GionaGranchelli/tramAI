package dev.tramai.persistence.jdbc

/**
 * Minimal marker for the Sovereign JDBC Persistence module.
 *
 * This module is the first implementation foundation for the production-hardening
 * design defined in docs/architecture/sovereign-jdbc-persistence-design.md.
 *
 * Full JDBC stores (JdbcApprovalStore, JdbcAuditStore, etc.) are not
 * implemented yet — only the schema skeleton and contract tests exist.
 */
internal object SovereignJdbcPersistence {
    const val MODULE_NAME: String = "tramai-persistence-jdbc"
}
