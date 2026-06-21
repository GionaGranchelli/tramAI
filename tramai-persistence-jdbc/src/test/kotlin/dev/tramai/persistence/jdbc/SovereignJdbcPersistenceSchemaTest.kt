package dev.tramai.persistence.jdbc

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SovereignJdbcPersistenceSchemaTest {

    private lateinit var connection: Connection

    @BeforeEach
    fun setUp() {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:sovereign_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        )
        val schemaSql = this::class.java.classLoader
            .getResource("tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql")
            ?.readText()
            ?: throw IllegalStateException("Schema SQL resource not found")
        connection.createStatement().use { stmt ->
            stmt.execute(schemaSql)
        }
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
        assertUniqueIndex("audit_events", "event_id")
    }

    @Test
    fun `suspended_invocations has unique replay_envelope_digest`() {
        assertUniqueIndex("suspended_invocations", "replay_envelope_digest")
    }

    @Test
    fun `audit_outbox has unique event_key`() {
        assertUniqueIndex("audit_outbox", "event_key")
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

    // ── Helpers ─────────────────────────────────────────────────

    private fun queryTables(): List<String> =
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'PUBLIC'
                ORDER BY table_name
                """.trimIndent()
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
                WHERE table_schema = 'PUBLIC' AND table_name = '${table.uppercase()}'
                ORDER BY ordinal_position
                """.trimIndent()
            ).let { rs ->
                generateSequence { if (rs.next()) rs.getString("column_name") else null }.toList()
            }
        }

    private fun queryPrimaryKeyColumns(table: String): Set<String> {
        val rs = connection.metaData.getPrimaryKeys(null, "PUBLIC", table.uppercase())
        return generateSequence { if (rs.next()) rs.getString("COLUMN_NAME") else null }.toSet()
    }

    /** Use JDBC metadata getIndexInfo — portable across H2 and PostgreSQL. */
    private fun queryUniqueIndexes(table: String): Set<String> {
        val rs = connection.metaData.getIndexInfo(null, "PUBLIC", table.uppercase(), false, false)
        val indexes = mutableMapOf<String, MutableSet<String>>()
        while (rs.next()) {
            val indexName = rs.getString("INDEX_NAME") ?: continue
            val columnName = rs.getString("COLUMN_NAME") ?: continue
            val nonUnique = rs.getBoolean("NON_UNIQUE")
            if (!nonUnique) {
                indexes.computeIfAbsent(indexName) { mutableSetOf() }.add(columnName)
            }
        }
        return indexes.values.flatten().toSet()
    }

    private fun assertPrimaryKey(table: String, expectedColumns: Set<String>) {
        val actual = queryPrimaryKeyColumns(table).map { it.uppercase() }.toSet()
        val expected = expectedColumns.map { it.uppercase() }.toSet()
        assertEquals(expected, actual, "Primary key mismatch for $table")
    }

    private fun assertUniqueIndex(table: String, column: String) {
        val uniqueColumns = queryUniqueIndexes(table).map { it.uppercase() }
        assertTrue(
            column.uppercase() in uniqueColumns,
            "Expected unique index on $table($column). Found unique indexes on: $uniqueColumns"
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
}
