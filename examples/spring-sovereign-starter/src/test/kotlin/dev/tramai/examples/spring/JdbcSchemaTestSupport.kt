package dev.tramai.examples.spring

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Test-only support for applying TramAI JDBC schema migrations to a
 * Testcontainers-managed PostgreSQL database.
 *
 * Reads the SQL migration resources from `tramai-persistence-jdbc` and
 * executes their statements sequentially in one transaction per resource
 * on the given [DataSource]. This is intentionally kept as test support
 * only — production migration execution is a separate concern
 * (Flyway/Liquibase integration, runbook).
 */
object JdbcSchemaTestSupport {

    private val MIGRATION_RESOURCES: List<String> = listOf(
        "/tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql",
        "/tramai/persistence/jdbc/postgres/V2__approval_continuations.sql",
        "/tramai/persistence/jdbc/postgres/V3__audit_events_hardening.sql",
        "/tramai/persistence/jdbc/postgres/V4__audit_outbox_hardening.sql",
        "/tramai/persistence/jdbc/postgres/V5__worker_leases_hardening.sql",
    )

    /**
     * Applies all TramAI JDBC schema migrations to the given [dataSource].
     * Each migration is read from the classpath and its statements are
     * executed sequentially in one transaction.
     *
     * @throws IllegalStateException if any migration fails.
     */
    fun applyMigrations(dataSource: DataSource) {
        for (resource in MIGRATION_RESOURCES) {
            executeResource(dataSource, resource)
        }
    }

    private fun executeResource(dataSource: DataSource, resource: String) {
        val sql = readResource(resource)
        if (sql.isBlank()) return

        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt ->
                    // Split on semicolons and execute each statement
                    val statements = splitStatements(sql)
                    for (statement in statements) {
                        if (statement.isNotBlank()) {
                            stmt.execute(statement)
                        }
                    }
                }
                conn.commit()
            } catch (e: SQLException) {
                conn.rollback()
                throw IllegalStateException(
                    "Migration failed on resource [$resource]: ${e.message}",
                    e,
                )
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    private fun readResource(resource: String): String {
        val inputStream = JdbcSchemaTestSupport::class.java.getResourceAsStream(resource)
            ?: throw IllegalStateException("Migration resource not found: $resource")

        return inputStream.bufferedReader().use { reader ->
            reader.readText()
        }
    }

    /**
     * Splits a SQL script into individual statements on semicolons,
     * skipping comment lines and blank lines.
     */
    private fun splitStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        for (line in sql.lines()) {
            val trimmed = line.trim()
            // Skip comments and blank lines
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue
            current.append(line).append("\n")
            if (trimmed.endsWith(";")) {
                statements.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotBlank()) {
            statements.add(current.toString())
        }
        return statements
    }
}
