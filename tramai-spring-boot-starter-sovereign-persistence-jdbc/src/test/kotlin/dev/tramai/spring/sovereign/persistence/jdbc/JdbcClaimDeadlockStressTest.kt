package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.spring.sovereign.ops.outbox.RefusalEvidence
import dev.tramai.spring.sovereign.ops.outbox.RefusalOperation
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * STAB-0.7.6-001 reproduction harness for Issue #568: concurrent claimers on a contended batch, with the
 * blocking `FOR UPDATE` fallback engaged by a holder that hides the eligible rows.
 *
 * This is a *qualification* harness, not a pass/fail test of the deadlock itself: it records every SQLSTATE
 * it observes and asserts the invariants that must hold regardless (no duplicate claims, no lost claimable
 * rows, no foreign lease ownership). Whether 40P01 appears is data, printed and asserted separately, so the
 * same harness can be run against a candidate fix and against the base commit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcClaimDeadlockStressTest {
    private lateinit var dataSource: DataSource
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneOffset.UTC)

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
                // A real pool-sized server: contention must come from the workload, not from a single client.
                setProperty("prepareThreshold", "0")
            }
        val base =
            Paths
                .get(System.getProperty("user.dir"))
                .parent
                .resolve("tramai-persistence-jdbc/src/main/resources/tramai/persistence/jdbc/postgres")
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                for (file in MIGRATIONS) stmt.execute(Files.readString(base.resolve(file)))
            }
        }
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun clean() {
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE audit_outbox, audit_outbox_quarantine, approvals CASCADE")
            }
        }
    }

    @Test
    fun `concurrent claimers on a contended batch keep the claim invariants`() =
        runBlocking<Unit> {
            val sqlStates = mutableMapOf<String, Int>()
            var deadlocks = 0
            var successfulClaims = 0

            repeat(ROUNDS) { round ->
                seed(BATCH)
                val claimed = mutableListOf<String>()
                val claims =
                    (1..CLAIMERS)
                        .map { worker ->
                            async(Dispatchers.IO) {
                                try {
                                    val rows = store().claimPending("worker-$worker", CLAIM_LIMIT, clock.instant())
                                    rows.map { it.outboxId to it.claimedBy }
                                } catch (e: PSQLException) {
                                    val state = e.sqlState ?: "unknown"
                                    synchronized(sqlStates) { sqlStates[state] = (sqlStates[state] ?: 0) + 1 }
                                    synchronized(sqlStates) { if (state == DEADLOCK_SQLSTATE) deadlocks++ }
                                    emptyList()
                                }
                            }
                        }.awaitAll()

                synchronized(claimed) {
                    claims.flatten().forEach { (id, owner) ->
                        claimed.add(id)
                        successfulClaims++
                    }
                }
                // No row may be claimed by two workers in the same round: a claim is exclusive.
                assertThat(claims.flatten().map { it.first })
                    .describedAs("round $round claimed the same row twice")
                    .doesNotHaveDuplicates()
                // Every row is claimable here, so a round must claim the whole batch: a short round means
                // the claim path lost rows, and that must fail rather than print.
                assertThat(claims.flatten()).describedAs("round $round claim count").hasSize(BATCH)
                // A worker never claims a row on behalf of another worker.
                claims.flatten().forEach { (id, owner) ->
                    // This scenario seeds only decodable rows, so the claim owner is readable evidence.
                    assertThat(store().get(id)?.claimedBy)
                        .describedAs("round $round row $id claimed by $owner")
                        .isEqualTo(owner)
                }
                if (round % 5 == 0) println("[stress] round $round claims=$successfulClaims sqlStates=$sqlStates")
            }

            println(
                "[stress] SUMMARY rounds=$ROUNDS batch=$BATCH claimers=$CLAIMERS " +
                    "sqlStates=$sqlStates deadlocks=$deadlocks claims=$successfulClaims",
            )
            // The harness fails on the thing it is qualifying: a deadlock, or any unexpected SQLSTATE.
            assertThat(deadlocks).describedAs("40P01 observed: $sqlStates").isZero()
            assertThat(sqlStates.keys).describedAs("unexpected SQLSTATEs").allMatch { it == EXPECTED_NONE }
        }

    @Test
    fun `concurrent claimers plus terminal transitions keep the claim invariants`() =
        runBlocking<Unit> {
            val sqlStates = mutableMapOf<String, Int>()
            repeat(ROUNDS) { round ->
                seed(BATCH)
                val claimers =
                    (1..CLAIMERS).map { worker ->
                        async(Dispatchers.IO) {
                            runCatching { store().claimPending("worker-$worker", CLAIM_LIMIT, clock.instant()) }
                                .onFailure { e ->
                                    val state = (e as? PSQLException)?.sqlState ?: "non-sql:${e::class.simpleName}"
                                    synchronized(sqlStates) { sqlStates[state] = (sqlStates[state] ?: 0) + 1 }
                                }.getOrDefault(emptyList())
                        }
                    }
                // A concurrent terminal transition holds locks on the rows a claimer just claimed, which is
                // exactly what makes the fast path return nothing and engages the blocking fallback.
                val transitions =
                    (1..TRANSITIONS).map {
                        async(Dispatchers.IO) {
                            repeat(6) {
                                val target =
                                    store().listByStatus(SovereignOpsAuditOutboxStatus.EMITTING, 1).firstOrNull()
                                if (target != null) {
                                    // A lost race is expected: another worker already moved the row.
                                    runCatching {
                                        store().markEmitted(
                                            target.outboxId,
                                            SovereignOpsAuditOutboxStatus.EMITTING,
                                            target.attemptCount,
                                            clock.instant(),
                                        )
                                    }.onFailure { e ->
                                        val state = (e as? PSQLException)?.sqlState ?: "benign:${e::class.simpleName}"
                                        synchronized(sqlStates) { sqlStates[state] = (sqlStates[state] ?: 0) + 1 }
                                    }
                                }
                                delay(1)
                            }
                        }
                    }
                claimers.awaitAll()
                transitions.awaitAll()
                if (round % 5 == 0) println("[stress2] round $round sqlStates=$sqlStates")
            }
            println("[stress2] SUMMARY rounds=$ROUNDS batch=$BATCH claimers=$CLAIMERS sqlStates=$sqlStates")
            assertThat(sqlStates.keys)
                .describedAs("only a lost race may surface, never a SQLSTATE")
                .allMatch { it == BENIGN_LOST_RACE }
        }

    @Test
    fun `claimers and the quarantine scan keep the evidence invariants`() =
        runBlocking<Unit> {
            val sqlStates = mutableMapOf<String, Int>()
            repeat(ROUNDS) { round ->
                seed(BATCH, undecodableEvery = 3)
                val claimers =
                    (1..CLAIMERS).map { worker ->
                        async(Dispatchers.IO) {
                            runCatching { store().claimPending("worker-$worker", CLAIM_LIMIT, clock.instant()) }
                                .onFailure { e -> synchronized(sqlStates) { bump(sqlStates, e) } }
                                .getOrDefault(emptyList())
                        }
                    }
                val quarantine =
                    (1..QUARANTINERS).map {
                        async(Dispatchers.IO) {
                            repeat(3) {
                                runCatching {
                                    store().quarantineStore()?.quarantineUndecodableEvidence(3, clock.instant())
                                }.onFailure { e -> synchronized(sqlStates) { bump(sqlStates, e) } }
                                delay(1)
                            }
                        }
                    }
                claimers.awaitAll()
                quarantine.awaitAll()

                // Quarantining never rewrites stored bytes, and never loses a row.
                val quarantined = store().quarantineStore()?.listQuarantined(50) ?: emptyList()
                assertThat(countRows()).describedAs("round $round lost evidence rows").isEqualTo(BATCH)
                assertThat(quarantined)
                    .describedAs("round $round quarantined nothing although the batch contained damage")
                    .isNotEmpty()
                quarantined.forEach { summary ->
                    val stored = payloadBytes(summary.outboxId!!)
                    val kept = store().quarantineStore()?.readQuarantinedCiphertext(summary.locator)
                    assertThat(kept)
                        .describedAs("round $round quarantined ciphertext missing for ${summary.locator}")
                        .isNotNull
                    // Byte-identical: quarantine preserves evidence, it does not re-encode it.
                    assertThat(kept).isEqualTo(stored)
                }
                if (round % 5 == 0) {
                    println("[stress3] round $round quarantined=${quarantined.size} sqlStates=$sqlStates")
                }
            }
            println(
                "[stress3] SUMMARY rounds=$ROUNDS batch=$BATCH claimers=$CLAIMERS sqlStates=$sqlStates",
            )
            assertThat(sqlStates.keys)
                .describedAs("a decode failure is expected; a SQLSTATE is not")
                .allMatch { it == EXPECTED_DECODE_FAILURE }
        }

    @Test
    fun `claim order is total when every row shares a timestamp`() =
        runBlocking<Unit> {
            // Written back to front on purpose: with a non-total ORDER BY the tied rows come back in heap
            // order, i.e. descending, and the claim order is neither chronological nor stable.
            dataSource.connection.use { conn ->
                conn.createStatement().use {
                    it.execute("TRUNCATE TABLE audit_outbox, audit_outbox_quarantine CASCADE")
                }
            }
            val store = store()
            for (i in BATCH downTo 1) runBlocking { store.appendDispatchable(pending(i)) }

            val claimed = store.claimPending("worker-a", BATCH, clock.instant())

            assertThat(claimed.map { it.outboxId })
                .describedAs("tied created_at must be broken by outbox_id")
                .isEqualTo((1..BATCH).map { "row-%03d".format(it) })
        }

    @Test
    fun `a candidate whose stored state diverges after the probe fails closed`() =
        runBlocking<Unit> {
            seed(3)
            // A holder locks every row, so the SKIP LOCKED fast path finds nothing and the fallback runs.
            dataSource.connection.use { holder ->
                holder.autoCommit = false
                holder.createStatement().use {
                    it.execute("SELECT outbox_id FROM audit_outbox ORDER BY created_at, outbox_id FOR UPDATE")
                }
                // Captured rather than thrown: a failing child would cancel this scope before assertions run.
                val claim =
                    async(Dispatchers.IO) {
                        runCatching { store().claimPending("worker-a", 1, clock.instant()) }
                    }
                delay(300) // the claimer is now blocked inside the fallback, holding nothing yet
                // By the time it acquires the row it probed, that row is no longer dispatchable.
                // The other transaction emits it: a state change the non-locking probe cannot see. Raw SQL
                // because this transition belongs to that transaction, not to the store under test.
                holder.createStatement().use {
                    it.execute(
                        "UPDATE audit_outbox SET status = 'EMITTED', dispatched_at = now() " +
                            "WHERE outbox_id = 'row-001'",
                    )
                }
                holder.commit()

                // The row the probe listed is no longer usable once locked. The store detects the divergence
                // (column and payload now disagree) instead of skipping it or claiming it as-is, and the
                // failed claim releases the row lock it had taken.
                assertThat(claim.await().exceptionOrNull())
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("audit-outbox-column-status-mismatch")
                assertThat(attemptCount("row-001")).isZero()
                assertThat(statusOf("row-001")).isEqualTo("EMITTED")
                assertThat(canLockNowait("row-001"))
                    .describedAs("a failed claim must not keep the row lock")
                    .isTrue()
            }
        }

    // --- helpers --------------------------------------------------------------------------------

    private fun seed(
        count: Int,
        undecodableEvery: Int? = null,
    ) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE audit_outbox, audit_outbox_quarantine CASCADE") }
        }
        val store = store()
        for (i in 1..count) {
            runBlocking { store.appendDispatchable(pending(i, undecodableEvery?.let { i % it == 0 } ?: false)) }
        }
    }

    private fun pending(
        index: Int,
        undecodable: Boolean = false,
    ): SovereignOpsAuditOutboxRecord =
        RefusalEvidence(
            operation = RefusalOperation.APPROVE,
            approvalId = "approval-$index",
            issuer = "https://issuer.example",
            subject = "operator-$index",
            // Only the quarantine scenario seeds genuinely undecodable rows: that path needs real work.
            refusalReason = if (undecodable) "stress-probe-$UNDECODABLE_MARKER" else "stress-probe",
            policyVersion = null,
            expectedVersion = 1,
            correlationId = "corr-$index",
        ).toOutboxRecord(clock.instant(), outboxId = "row-%03d".format(index))
            .copy(eventKey = "key-$index")

    private fun bump(
        counts: MutableMap<String, Int>,
        e: Throwable,
    ) {
        val state = (e as? PSQLException)?.sqlState ?: "non-sql:${e::class.simpleName}"
        counts[state] = (counts[state] ?: 0) + 1
        // Lock evidence belongs with the SQLSTATE: a bare 40P01 says nothing about who held what.
        if (state == DEADLOCK_SQLSTATE) captureLockEvidence(state, e)
    }

    private fun captureLockEvidence(
        state: String,
        e: Throwable,
    ) {
        println("[stress] DEADLOCK $state: ${e.message}")
        lockRows().forEach { println("[stress] lock $it") }
        postgres.logs.lines().filter { it.contains("deadlock detected") }.forEach {
            println("[stress] server: $it")
        }
    }

    /** One line per backend lock on the outbox, so a dump needs no nested loops to read. */
    private fun lockRows(): List<String> =
        query(
            "SELECT l.pid, l.mode, l.granted, a.state, a.wait_event_type, a.query " +
                "FROM pg_locks l LEFT JOIN pg_stat_activity a ON a.pid = l.pid " +
                "WHERE l.relation = 'audit_outbox'::regclass ORDER BY l.pid",
        ) { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        "pid=${rs.getInt(1)} mode=${rs.getString(2)} granted=${rs.getBoolean(3)} " +
                            "state=${rs.getString(4)} wait=${rs.getString(5)} " +
                            "query=${rs.getString(6)?.take(120)}",
                    )
                }
            }
        }

    private fun statusOf(outboxId: String): String? =
        query("SELECT status FROM audit_outbox WHERE outbox_id = '$outboxId'") { rs ->
            if (rs.next()) rs.getString(1) else null
        }

    private fun attemptCount(outboxId: String): Int =
        query("SELECT attempt_count FROM audit_outbox WHERE outbox_id = '$outboxId'") { rs ->
            rs.next()
            rs.getInt(1)
        }

    private fun payloadBytes(outboxId: String): ByteArray? =
        query("SELECT encrypted_payload FROM audit_outbox WHERE outbox_id = '$outboxId'") { rs ->
            if (rs.next()) rs.getBytes(1) else null
        }

    /** True when nobody still holds the row's lock, i.e. the claim transaction released it. */
    private fun canLockNowait(outboxId: String): Boolean =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use {
                    it.execute("SELECT 1 FROM audit_outbox WHERE outbox_id = '$outboxId' FOR UPDATE NOWAIT")
                }
                true
            } catch (e: PSQLException) {
                if (e.sqlState == LOCK_NOT_AVAILABLE_SQLSTATE) false else throw e
            } finally {
                conn.rollback()
            }
        }

    private fun <T> query(
        sql: String,
        read: (ResultSet) -> T,
    ): T = dataSource.connection.use { conn -> conn.createStatement().use { stmt -> stmt.executeQuery(sql).use(read) } }

    private fun countRows(): Int =
        query("SELECT count(*) FROM audit_outbox") { rs ->
            rs.next()
            rs.getInt(1)
        }

    private fun store(): JdbcSovereignOpsAuditOutboxStore =
        JdbcSovereignOpsAuditOutboxStore(
            dataSource = dataSource,
            payloadCodec = PassThroughCodec(),
            claimLeaseDuration = Duration.ofSeconds(30),
            maxClaimLimit = BATCH,
        )

    /** Identity codec: this harness measures lock behaviour, not encryption. */
    private class PassThroughCodec : JdbcOpsAuditOutboxPayloadCodec {
        override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload =
            JdbcEncryptedAuditOutboxPayload(
                ciphertext = plaintext,
                keyId = KEY_ID,
                algorithm = "PLAIN",
                nonce = ByteArray(NONCE_LENGTH),
                payloadDigest =
                    "sha256:" +
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(plaintext)
                            .joinToString("") { "%02x".format(it) },
            )

        override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray {
            // Every third seeded row is genuinely undecodable: the quarantine path must have real work.
            require(!String(envelope.ciphertext).contains(UNDECODABLE_MARKER)) {
                "audit-outbox-payload-decryption-failed"
            }
            return envelope.ciphertext
        }
    }

    private companion object {
        private const val ROUNDS = 20
        private const val BATCH = 12
        private const val CLAIMERS = 8
        private const val CLAIM_LIMIT = 2
        private const val TRANSITIONS = 4
        private const val QUARANTINERS = 3
        private const val UNDECODABLE_MARKER = "quarantine-me"
        private const val DEADLOCK_SQLSTATE = "40P01"
        private const val LOCK_NOT_AVAILABLE_SQLSTATE = "55P03"
        private const val EXPECTED_NONE = "<none>"
        private const val BENIGN_LOST_RACE = "benign:IllegalArgumentException"
        private const val EXPECTED_DECODE_FAILURE = "non-sql:IllegalStateException"
        private const val NONCE_LENGTH = 12
        private const val KEY_ID = "stress-key"

        private val MIGRATIONS =
            listOf(
                "V1__sovereign_persistence.sql",
                "V4__audit_outbox_hardening.sql",
                "V10__approval_actor_provenance.sql",
            )

        private val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("claim_stress")
                .withUsername("test")
                .withPassword("test")
                .withCommand("postgres", "-c", "max_connections=200", "-c", "deadlock_timeout=200ms")
    }
}
