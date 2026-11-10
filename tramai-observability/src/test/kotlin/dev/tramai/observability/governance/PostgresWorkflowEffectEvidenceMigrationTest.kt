package dev.tramai.observability.governance

import dev.tramai.orchestration.testing.WorkflowEffectEvidencePostgresMigrationRunner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

/**
 * D7-E1 qualification requirement:
 * Automated integration test executing the E9 schema migration against a genuinely
 * pre-populated E1–E8 evidence database on real PostgreSQL, verifying:
 * - Data preservation across migration
 * - Successful backfill of parent_id from step_attempt_id
 * - Constraint enforcement (NOT NULL on parent_id)
 * - Mixed reading of legacy and authentic E9 records through typed parent reader/writer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresWorkflowEffectEvidenceMigrationTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("evidence_migration")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
    }

    @AfterAll
    fun tearDownAll() {
        if (::postgres.isInitialized) {
            postgres.stop()
        }
    }

    @Test
    fun `E9 PostgreSQL migration preserves legacy E1-E8 evidence and supports typed parent writes and reads`() {
        val runner = WorkflowEffectEvidencePostgresMigrationRunner(dataSource)
        runner.runMigrationAndVerification()
    }
}
