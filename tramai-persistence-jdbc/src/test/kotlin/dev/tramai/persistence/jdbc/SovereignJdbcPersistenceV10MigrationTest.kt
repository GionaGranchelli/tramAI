package dev.tramai.persistence.jdbc

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.7.6f V10 -- approval actor provenance and quarantine metadata.
 *
 * Every test gets its **own schema**, built from V1..V9 and migrated on its own. That is the point: the
 * populated-upgrade case must observe V10 actually running against rows that existed before it, so the
 * test cannot depend on whether another test happened to migrate first. A shared database with an
 * "apply if missing" helper would let the upgrade test pass while proving nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SovereignJdbcPersistenceV10MigrationTest {
    private lateinit var connection: Connection
    private lateinit var schema: String

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private const val V10 = "V10__approval_actor_provenance.sql"
        private val PRE_V10 =
            listOf(
                "V1__sovereign_persistence.sql",
                "V2__approval_continuations.sql",
                "V3__audit_events_hardening.sql",
                "V4__audit_outbox_hardening.sql",
                "V5__worker_leases_hardening.sql",
                "V6__approval_resume_credential_custody.sql",
                "V7__approval_continuations_resume_retry.sql",
                "V8__control_plane_workload_registration.sql",
                "V9__model_attempt_observations.sql",
            )
        private val postgres =
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("sovereign_v10")
                .withUsername("test")
                .withPassword("test")
        private val counter = AtomicInteger()
    }

    @BeforeAll
    fun startPostgres() {
        postgres.start()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun createIsolatedSchema() {
        schema = "v10_case_${counter.incrementAndGet()}"
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        connection.createStatement().use { it.execute("SET search_path TO $schema") }
        PRE_V10.forEach(::applyMigration)
    }

    @AfterEach
    fun closeConnection() {
        if (::connection.isInitialized) connection.close()
    }

    // --- Upgrade path: a populated V9 database keeps historical rows honest ---

    @Test
    fun `upgrading a populated V9 schema preserves the legacy row and leaves provenance unknown`() {
        val legacyHash = "sha256:${"a".repeat(64)}"
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO approvals (approval_id, status, created_at, decision_actor_hash, version)
                VALUES ('legacy-approval', 'APPROVED', now(), '$legacyHash', 3)
                """.trimIndent(),
            )
        }

        // The columns must genuinely not exist yet, otherwise the migration below proves nothing.
        assertEquals(emptyList(), provenanceColumns(), "V9 must not already declare the V10 columns")

        applyMigration(V10)

        assertEquals(
            setOf("decision_actor_identity_digest", "decision_actor_issuer"),
            provenanceColumns().toSet(),
            "V10 must add both provenance columns",
        )
        connection.createStatement().use { stmt ->
            stmt
                .executeQuery(
                    """
                    SELECT decision_actor_hash, decision_actor_issuer, decision_actor_identity_digest
                    FROM approvals WHERE approval_id = 'legacy-approval'
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next(), "the legacy row must survive the migration")
                    assertEquals(legacyHash, rs.getString(1), "decision_actor_hash is frozen as-is")
                    assertNull(rs.getString(2), "a legacy row's issuer is unknown, never inferred")
                    assertNull(rs.getString(3), "no identity digest may be fabricated for a legacy row")
                }
        }
    }

    // --- Fresh path and vocabulary shape ---

    @Test
    fun `V10 applies to an empty V9 schema and adds both columns as nullable`() {
        applyMigration(V10)

        val columns =
            connection.createStatement().use { stmt ->
                stmt
                    .executeQuery(
                        """
                        SELECT column_name, is_nullable FROM information_schema.columns
                        WHERE table_schema = '$schema' AND table_name = 'approvals'
                          AND column_name IN ('decision_actor_issuer', 'decision_actor_identity_digest')
                        """.trimIndent(),
                    ).use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                    }
            }

        assertEquals(
            setOf(
                "decision_actor_issuer" to "YES",
                "decision_actor_identity_digest" to "YES",
            ),
            columns.toSet(),
            "both provenance columns must exist and be nullable, so no legacy row can be invalidated",
        )
    }

    @Test
    fun `the quarantine table exists with the contract's metadata columns`() {
        applyMigration(V10)

        assertEquals(
            listOf("failure_detail", "label", "outbox_id", "payload_digest", "quarantined_at"),
            columnNames("audit_outbox_quarantine").sorted(),
        )
    }

    @Test
    fun `the quarantine label constraint rejects an unknown label`() {
        applyMigration(V10)
        connection.createStatement().use { stmt ->
            stmt.execute("INSERT INTO audit_outbox (outbox_id, event_key, status) VALUES ('q-1', 'key-q-1', 'PENDING')")
        }

        assertFailsWith<SQLException> {
            connection.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO audit_outbox_quarantine (outbox_id, label) " +
                        "VALUES ('q-1', 'some-other-reason')",
                )
            }
        }
    }

    @Test
    fun `the identity digest column rejects anything that is not 64 lowercase hex`() {
        applyMigration(V10)

        assertFailsWith<SQLException> {
            connection.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO approvals (
                        approval_id, status, created_at, version,
                        decision_actor_issuer, decision_actor_identity_digest
                    ) VALUES ('bad-digest', 'PENDING', now(), 1, 'https://issuer.example', 'sha256:${"A".repeat(64)}')
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `a half-recorded provenance pair is rejected by the database`() {
        applyMigration(V10)

        assertFailsWith<SQLException> {
            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO approvals (
                        approval_id, status, created_at, version, decision_actor_issuer
                    ) VALUES ('issuer-only', 'PENDING', now(), 1, 'https://issuer.example')
                    """.trimIndent(),
                )
            }
        }
        assertFailsWith<SQLException> {
            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO approvals (
                        approval_id, status, created_at, version, decision_actor_identity_digest
                    ) VALUES ('digest-only', 'PENDING', now(), 1, '${"b".repeat(64)}')
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `a stored identity pair survives a round-trip unchanged`() {
        applyMigration(V10)
        val digest = "0123456789abcdef".repeat(4)
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO approvals (
                    approval_id, status, created_at, version,
                    decision_actor_issuer, decision_actor_identity_digest
                ) VALUES ('digest-ok', 'PENDING', now(), 1, 'https://issuer.example', '$digest')
                """.trimIndent(),
            )
            stmt
                .executeQuery(
                    "SELECT decision_actor_issuer, decision_actor_identity_digest " +
                        "FROM approvals WHERE approval_id = 'digest-ok'",
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("https://issuer.example", rs.getString(1))
                    assertEquals(digest, rs.getString(2))
                }
        }
    }

    // --- helpers ---

    private fun applyMigration(file: String) {
        val sql =
            javaClass.classLoader
                .getResource("tramai/persistence/jdbc/postgres/$file")
                ?.readText()
                ?: error("migration $file not found")
        connection.createStatement().use { it.execute(sql) }
    }

    private fun provenanceColumns(): List<String> =
        columnNames("approvals").filter { it.startsWith("decision_actor_") && it != "decision_actor_hash" }

    private fun columnNames(table: String): List<String> =
        connection.createStatement().use { stmt ->
            stmt
                .executeQuery(
                    """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = '$schema' AND table_name = '$table'
                    """.trimIndent(),
                ).use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
        }
}
