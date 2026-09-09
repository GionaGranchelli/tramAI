package dev.tramai.persistence.jdbc

import dev.tramai.controlplane.WorkloadRegistrationStore
import dev.tramai.controlplane.testing.WorkloadRegistrationStoreTck
import dev.tramai.controlplane.testing.WorkloadRegistrationStoreTckHarness
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * The JDBC durable implementation must satisfy the shared
 * [WorkloadRegistrationStore] contract (Epic 0.7.1c). The runner owns the
 * datasource + schema; storage technology never contaminates the contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWorkloadRegistrationStoreTckTest : WorkloadRegistrationStoreTck() {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("control_plane_tck")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        executeSchema(dataSource)
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { postgres.stop() }
    }

    override val harness =
        object : WorkloadRegistrationStoreTckHarness {
            override fun createStore(): WorkloadRegistrationStore {
                // Fresh isolated storage per case.
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("DELETE FROM tramai_workload_registration")
                        stmt.execute("DELETE FROM tramai_configuration_revision")
                    }
                }
                return JdbcWorkloadRegistrationStore(dataSource)
            }
        }

    companion object {
        internal fun executeSchema(dataSource: DataSource) {
            dataSource.connection.use { conn ->
                val migrations =
                    listOf(
                        "V1__sovereign_persistence.sql",
                        "V2__approval_continuations.sql",
                        "V3__audit_events_hardening.sql",
                        "V4__audit_outbox_hardening.sql",
                        "V5__worker_leases_hardening.sql",
                        "V6__approval_resume_credential_custody.sql",
                        "V7__approval_continuations_resume_retry.sql",
                        "V8__control_plane_workload_registration.sql",
                    )
                for (migration in migrations) {
                    val sql =
                        JdbcWorkloadRegistrationStoreTckTest::class.java.classLoader
                            .getResource("tramai/persistence/jdbc/postgres/$migration")
                            ?.readText()
                            ?: throw IllegalStateException("Schema SQL resource not found: $migration")
                    conn.createStatement().use { stmt -> stmt.execute(sql) }
                }
            }
        }
    }
}
