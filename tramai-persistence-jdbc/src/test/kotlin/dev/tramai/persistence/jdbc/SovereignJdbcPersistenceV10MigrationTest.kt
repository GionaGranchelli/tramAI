package dev.tramai.persistence.jdbc

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.7.6f V10 -- approval actor provenance and quarantine metadata.
 *
 * Two paths are asserted because they carry different risks: applying V10 to an empty database (a fresh
 * install, where V1..V10 run in order) and applying it to a **populated** V9 database (an upgrade, where
 * the change could invent provenance for rows written before it existed). The populated path is the one
 * that matters most: a legacy approval row must come out of the migration with an explicitly unknown
 * issuer, never with an inferred one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SovereignJdbcPersistenceV10MigrationTest {
    private lateinit var connection: Connection

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val MIGRATIONS =
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
        private const val V10 = "V10__approval_actor_provenance.sql"

        private val postgres =
            PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("sovereign_v10")
                .withUsername("test")
                .withPassword("test")
    }

    @BeforeAll
    fun setUpAll() {
        postgres.start()
        connection =
            DriverManager.getConnection(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        MIGRATIONS.forEach(::applyMigration)
    }

    @AfterAll
    fun tearDownAll() {
        if (::connection.isInitialized) connection.close()
        postgres.stop()
    }

    // -- Upgrade path: a populated V9 database keeps historical rows honest --

    @Test
    fun `upgrading a populated V9 database leaves legacy provenance explicitly unknown`() {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO approvals (approval_id, status, created_at, decision_actor_hash, version)
                VALUES ('legacy-approval', 'APPROVED', now(), 'sha256:${"a".repeat(64)}', 3)
                """.trimIndent(),
            )
        }

        ensureV10()

        assertTrue(columnNames("approvals").contains("decision_actor_issuer"))
        assertTrue(columnNames("approvals").contains("decision_actor_identity_digest"))

        connection.createStatement().use { stmt ->
            stmt
                .executeQuery(
                    """
                    SELECT decision_actor_hash, decision_actor_issuer, decision_actor_identity_digest
                    FROM approvals WHERE approval_id = 'legacy-approval'
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next(), "the legacy row must survive the migration")
                    assertEquals("sha256:${"a".repeat(64)}", rs.getString(1), "decision_actor_hash is frozen as-is")
                    assertNull(rs.getString(2), "a legacy row's issuer is unknown, never inferred")
                    assertNull(rs.getString(3), "no identity digest may be fabricated for a legacy row")
                }
        }
    }

    // --- -- Fresh path and vocabulary shape ----------------------------------------------------------------

    @Test
    fun `V10 applies to an empty V9 database and adds both columns as nullable`() {
        ensureV10()

        val columns =
            connection.createStatement().use { stmt ->
                stmt
                    .executeQuery(
                        """
                        SELECT column_name, is_nullable FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'approvals'
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
        ensureV10()

        assertEquals(
            listOf("failure_detail", "label", "outbox_id", "payload_digest", "quarantined_at"),
            columnNames("audit_outbox_quarantine").sorted(),
        )
    }

    @Test
    fun `the quarantine label constraint rejects an unknown label`() {
        ensureV10()

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

        connection.createStatement().use { stmt ->
            stmt.execute("DELETE FROM audit_outbox WHERE outbox_id = 'q-1'")
        }
    }

    @Test
    fun `the identity digest column rejects anything that is not 64 lowercase hex`() {
        ensureV10()

        assertFailsWith<SQLException> {
            connection.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO approvals (approval_id, status, created_at, version, decision_actor_identity_digest)
                    VALUES ('bad-digest', 'PENDING', now(), 1, 'sha256:${"A".repeat(64)}')
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `a stored identity digest survives a round-trip unchanged`() {
        ensureV10()

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
            stmt.execute("DELETE FROM approvals WHERE approval_id = 'digest-ok'")
        }
    }

    // --- -- helpers ----------------------------------------------------------------------------------------

    /** Applies V10 once, so the tests are independent of execution order. */
    private fun ensureV10() {
        if (columnNames("approvals").contains("decision_actor_issuer")) return
        applyMigration(V10)
    }

    private fun applyMigration(file: String) {
        val sql =
            javaClass.classLoader
                .getResource("tramai/persistence/jdbc/postgres/$file")
                ?.readText()
                ?: error("migration $file not found")
        connection.createStatement().use { it.execute(sql) }
    }

    private fun columnNames(table: String): List<String> =
        connection.createStatement().use { stmt ->
            stmt
                .executeQuery(
                    """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = '$table'
                    """.trimIndent(),
                ).use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
        }
}
