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
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SovereignJdbcPersistenceSchemaTest {

    private lateinit var connection: Connection

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"

        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("sovereign_test")
            .withUsername("test")
            .withPassword("test")
    }

    @BeforeAll
    fun setUpAll() {
        postgres.start()
        connection = DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password
        )
        val schemaSql = this::class.java.classLoader
            .getResource("tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql")
            ?.readText()
            ?: throw IllegalStateException("Schema SQL resource not found")
        connection.createStatement().use { stmt ->
            stmt.execute(schemaSql)
        }
    }

    @AfterAll
    fun tearDownAll() {
        connection.close()
        postgres.stop()
    }

    // ── Table existence ─────────────────────────────────────────

    @Test
    fun `all five tables exist`() {
        val expected = setOf("approvals", "suspended_invocations", "audit_events", "audit_outbox", "worker_leases")
        val actual = queryTables().map { it.uppercase() }.toSet()
        assertEquals(expected.map { it.uppercase() }.toSet(), actual, "Schema must define all five sovereign persistence tables")
    }

    // ── Primary keys ─────────────────────────────────────────────

    @Test
    fun `approvals has primary key on approval_id`() {
        assertPrimaryKey("approvals", setOf("approval_id"))
    }

    @Test
    fun `suspended_invocations has primary key on invocation_id`() {
        assertPrimaryKey("suspended_invocations", setOf("invocation_id"))
    }

    @Test
    fun `audit_events has primary key on stream_id comma sequence_number`() {
        assertPrimaryKey("audit_events", setOf("stream_id", "sequence_number"))
    }

    @Test
    fun `audit_outbox has primary key on outbox_id`() {
        assertPrimaryKey("audit_outbox", setOf("outbox_id"))
    }

    @Test
    fun `worker_leases has primary key on lease_name`() {
        assertPrimaryKey("worker_leases", setOf("lease_name"))
    }

    // ── Unique constraints / indexes ─────────────────────────────

    @Test
    fun `audit_events has unique event_id`() {
        assertUniqueIndex("audit_events", setOf("event_id"))
    }

    @Test
    fun `suspended_invocations has unique replay_envelope_digest`() {
        assertUniqueIndex("suspended_invocations", setOf("replay_envelope_digest"))
    }

    @Test
    fun `audit_outbox has unique event_key`() {
        assertUniqueIndex("audit_outbox", setOf("event_key"))
    }

    // ── Operational indexes ──────────────────────────────────────

    @Test
    fun `idx_approvals_status_created_at exists`() {
        assertIndexExists("idx_approvals_status_created_at", "approvals")
    }

    @Test
    fun `idx_suspended_invocations_status_created_at exists`() {
        assertIndexExists("idx_suspended_invocations_status_created_at", "suspended_invocations")
    }

    @Test
    fun `idx_audit_outbox_status_next_attempt exists`() {
        assertIndexExists("idx_audit_outbox_status_next_attempt", "audit_outbox")
    }

    @Test
    fun `idx_audit_outbox_claimed_at exists`() {
        assertIndexExists("idx_audit_outbox_claimed_at", "audit_outbox")
    }

    @Test
    fun `idx_worker_leases_expires_at exists`() {
        assertIndexExists("idx_worker_leases_expires_at", "worker_leases")
    }

    // ── Column types ─────────────────────────────────────────────

    @Test
    fun `approvals sanitized_metadata is JSONB`() {
        assertColumnType("approvals", "sanitized_metadata", "jsonb")
    }

    @Test
    fun `approvals created_at is timestamptz`() {
        assertColumnType("approvals", "created_at", "timestamp with time zone")
    }

    @Test
    fun `audit_events occurred_at is timestamptz`() {
        assertColumnType("audit_events", "occurred_at", "timestamp with time zone")
    }

    @Test
    fun `audit_outbox created_at is timestamptz`() {
        assertColumnType("audit_outbox", "created_at", "timestamp with time zone")
    }

    @Test
    fun `worker_leases expires_at is timestamptz`() {
        assertColumnType("worker_leases", "expires_at", "timestamp with time zone")
    }

    // ── NOT NULL constraints ─────────────────────────────────────

    @Test
    fun `audit_events schema_version is NOT NULL`() {
        assertColumnNotNull("audit_events", "schema_version")
    }

    // ── Encryption metadata fields ───────────────────────────────

    @Test
    fun `approvals includes encryption metadata fields`() {
        assertEncryptionFields("approvals")
    }

    @Test
    fun `suspended_invocations includes encryption metadata fields`() {
        assertEncryptionFields("suspended_invocations")
    }

    @Test
    fun `audit_events includes encryption metadata fields`() {
        assertEncryptionFields("audit_events")
    }

    @Test
    fun `audit_outbox includes encryption metadata fields`() {
        assertEncryptionFields("audit_outbox")
    }

    // ── Encryption CHECK constraint tests ────────────────────────

    @Test
    fun `inserting encrypted_payload without encryption metadata must fail on approvals`() {
        assertFailsWith<SQLException>(
            "encrypted_payload requires complete encryption metadata"
        ) {
            connection.createStatement().execute(
                """
                INSERT INTO approvals (approval_id, status, encrypted_payload)
                VALUES ('a-enc-fail-1', 'PENDING', E'\\\\xdeadbeef')
                """
            )
        }
    }

    @Test
    fun `inserting encryption metadata without encrypted_payload must fail on approvals`() {
        assertFailsWith<SQLException>(
            "encryption metadata without encrypted_payload must fail"
        ) {
            connection.createStatement().execute(
                """
                INSERT INTO approvals (approval_id, status, encryption_key_id, encryption_algorithm, encryption_nonce, payload_digest)
                VALUES ('a-enc-fail-2', 'PENDING', 'key-1', 'AES-GCM', E'\\\\xabcd', 'digest-abc')
                """
            )
        }
    }

    @Test
    fun `inserting encrypted_replay_envelope without metadata must fail on suspended_invocations`() {
        assertFailsWith<SQLException> {
            connection.createStatement().execute(
                """
                INSERT INTO suspended_invocations (invocation_id, status, replay_envelope_digest, encrypted_replay_envelope)
                VALUES ('si-enc-fail-1', 'SUSPENDED', 'digest-xyz', E'\\\\xdeadbeef')
                """
            )
        }
    }

    // ── Domain-specific checks ──────────────────────────────────

    @Test
    fun `audit_outbox has correlation_key_hash`() {
        assertColumnExists("audit_outbox", "correlation_key_hash")
    }

    @Test
    fun `audit_outbox does not have raw claim_id`() {
        val columns = queryColumns("audit_outbox")
        assertTrue(
            columns.none { it.equals("claim_id", ignoreCase = true) },
            "audit_outbox must not contain a raw claim_id — use correlation_key_hash instead"
        )
    }

    // ── Negative constraint tests ────────────────────────────────

    @Test
    fun `duplicate audit_events event_id must fail with SQL state 23505`() {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO audit_events (stream_id, sequence_number, event_id, event_type, event_hash, schema_version)
                VALUES ('s1', 1, 'evt-001', 'TestEvent', 'hash-a', '1')
                """
            )
            val exception = assertFailsWith<SQLException> {
                stmt.execute(
                    """
                    INSERT INTO audit_events (stream_id, sequence_number, event_id, event_type, event_hash, schema_version)
                    VALUES ('s2', 1, 'evt-001', 'TestEvent', 'hash-b', '1')
                    """
                )
            }
            assertEquals("23505", exception.sqlState, "Expected unique violation SQL state 23505")
        }
    }

    @Test
    fun `duplicate audit stream sequence must fail with SQL state 23505`() {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO audit_events (stream_id, sequence_number, event_id, event_type, event_hash, schema_version)
                VALUES ('s-dup', 1, 'evt-dup-1', 'TestEvent', 'hash-x', '1')
                """
            )
            val exception = assertFailsWith<SQLException> {
                stmt.execute(
                    """
                    INSERT INTO audit_events (stream_id, sequence_number, event_id, event_type, event_hash, schema_version)
                    VALUES ('s-dup', 1, 'evt-dup-2', 'TestEvent', 'hash-y', '1')
                    """
                )
            }
            assertEquals("23505", exception.sqlState, "Expected unique violation SQL state 23505")
        }
    }

    @Test
    fun `duplicate suspended_invocations replay_envelope_digest must fail with SQL state 23505`() {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO suspended_invocations (invocation_id, status, replay_envelope_digest)
                VALUES ('inv-1', 'SUSPENDED', 'digest-001')
                """
            )
            val exception = assertFailsWith<SQLException> {
                stmt.execute(
                    """
                    INSERT INTO suspended_invocations (invocation_id, status, replay_envelope_digest)
                    VALUES ('inv-2', 'SUSPENDED', 'digest-001')
                    """
                )
            }
            assertEquals("23505", exception.sqlState, "Expected unique violation SQL state 23505")
        }
    }

    @Test
    fun `duplicate audit_outbox event_key must fail with SQL state 23505`() {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO audit_outbox (outbox_id, event_key, status)
                VALUES ('ob-1', 'key-001', 'PENDING')
                """
            )
            val exception = assertFailsWith<SQLException> {
                stmt.execute(
                    """
                    INSERT INTO audit_outbox (outbox_id, event_key, status)
                    VALUES ('ob-2', 'key-001', 'PENDING')
                    """
                )
            }
            assertEquals("23505", exception.sqlState, "Expected unique violation SQL state 23505")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun queryTables(): List<String> =
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                ORDER BY table_name
                """
            ).let { rs ->
                generateSequence { if (rs.next()) rs.getString("table_name") else null }.toList()
            }
        }

    private fun queryColumns(table: String): List<String> =
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = '${table.lowercase()}'
                ORDER BY ordinal_position
                """
            ).let { rs ->
                generateSequence { if (rs.next()) rs.getString("column_name") else null }.toList()
            }
        }

    private fun queryPrimaryKeyColumns(table: String): Set<String> {
        val rs = connection.metaData.getPrimaryKeys(null, null, table.lowercase())
        return generateSequence { if (rs.next()) rs.getString("COLUMN_NAME") else null }.toSet()
    }

    /** Returns the set of unique indexes, each as a sorted set of columns. */
    private fun queryUniqueIndexes(table: String): Set<Set<String>> {
        val rs = connection.metaData.getIndexInfo(null, null, table.lowercase(), false, false)
        val indexes = mutableMapOf<String, MutableSet<String>>()
        while (rs.next()) {
            val indexName = rs.getString("INDEX_NAME") ?: continue
            val columnName = rs.getString("COLUMN_NAME") ?: continue
            val nonUnique = rs.getBoolean("NON_UNIQUE")
            if (!nonUnique && indexName.startsWith("uq_")) {
                indexes.computeIfAbsent(indexName) { mutableSetOf() }.add(columnName)
            }
        }
        return indexes.values.map { it.map { c -> c.uppercase() }.toSet() }.toSet()
    }

    /** Returns the set of all index names for a table. */
    private fun queryIndexNames(table: String): Set<String> {
        val rs = connection.metaData.getIndexInfo(null, null, table.lowercase(), false, false)
        val names = mutableSetOf<String>()
        while (rs.next()) {
            val indexName = rs.getString("INDEX_NAME") ?: continue
            names.add(indexName.lowercase())
        }
        return names
    }

    private fun assertPrimaryKey(table: String, expectedColumns: Set<String>) {
        val actual = queryPrimaryKeyColumns(table).map { it.uppercase() }.toSet()
        val expected = expectedColumns.map { it.uppercase() }.toSet()
        assertEquals(expected, actual, "Primary key mismatch for $table")
    }

    private fun assertUniqueIndex(table: String, expectedColumns: Set<String>) {
        val indexes = queryUniqueIndexes(table)
        val expected = expectedColumns.map { it.uppercase() }.toSet()
        assertTrue(
            expected in indexes,
            "Expected exact unique index on $table(${expectedColumns.joinToString(", ")}). Found: $indexes"
        )
    }

    private fun assertIndexExists(indexName: String, table: String) {
        val names = queryIndexNames(table)
        assertTrue(
            indexName.lowercase() in names,
            "Expected index '$indexName' on $table. Found indexes: $names"
        )
    }

    private fun assertEncryptionFields(table: String) {
        val columns = queryColumns(table).map { it.uppercase() }
        listOf("encryption_key_id", "encryption_algorithm", "encryption_nonce", "payload_digest")
            .map { it.uppercase() }
            .forEach { field ->
                assertTrue(field in columns, "Encryption metadata field '$field' missing from $table")
            }
    }

    private fun assertColumnExists(table: String, column: String) {
        val columns = queryColumns(table).map { it.uppercase() }
        assertTrue(column.uppercase() in columns, "Expected column '$column' in $table")
    }

    private fun assertColumnType(table: String, column: String, expectedType: String) {
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = '${table.lowercase()}'
                  AND column_name = '${column.lowercase()}'
                """
            ).let { rs ->
                assertTrue(rs.next(), "Column '$column' not found in $table")
                val actual = rs.getString("data_type")
                assertEquals(expectedType.lowercase(), actual.lowercase(), "Column type mismatch for $table.$column")
            }
        }
    }

    private fun assertColumnNotNull(table: String, column: String) {
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = '${table.lowercase()}'
                  AND column_name = '${column.lowercase()}'
                """
            ).let { rs ->
                assertTrue(rs.next(), "Column '$column' not found in $table")
                assertEquals("NO", rs.getString("is_nullable"), "Column '$table.$column' should be NOT NULL")
            }
        }
    }
}
