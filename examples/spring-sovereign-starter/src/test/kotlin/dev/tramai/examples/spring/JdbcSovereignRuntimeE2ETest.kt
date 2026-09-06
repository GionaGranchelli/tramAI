package dev.tramai.examples.spring

import com.zaxxer.hikari.HikariDataSource
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.persistence.jdbc.JdbcApprovalContinuationStore
import dev.tramai.persistence.jdbc.JdbcApprovalStore
import dev.tramai.persistence.jdbc.JdbcAuditStore
import dev.tramai.persistence.jdbc.JdbcSuspendedInvocationStore
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.audit.calculateHash
import dev.tramai.spring.sovereign.SovereignTramaiAutoConfiguration
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseAcquisition
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.persistence.jdbc.SovereignJdbcPersistenceAutoConfiguration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * End-to-end tests proving that a Spring Boot TramAI sovereign runtime with
 * `tramai.sovereign.persistence.type=jdbc` survives context restart and recovers
 * all sovereign state from PostgreSQL.
 *
 * Uses embedded PostgreSQL (no Docker required), applies TramAI JDBC schema migrations,
 * and runs two separate Spring contexts to simulate restart.
 */
@org.junit.jupiter.api.Tag("e2e")
class JdbcSovereignRuntimeE2ETest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun startPg() {
            PgEmbeddedTestSupport.start()
        }

        @JvmStatic
        @AfterAll
        fun stopPg() {
            PgEmbeddedTestSupport.stop()
        }

        /** AES-256 key: 32 bytes (0..31) encoded in base64. */
        private val VALID_BASE64_KEY: String =
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    }

    @TempDir
    lateinit var tempDir: Path

    private lateinit var keyFile: Path

    @BeforeEach
    fun setUp() {
        keyFile = tempDir.resolve("e2e-key.b64")
        keyFile.toFile().writeText(VALID_BASE64_KEY)
    }

    // ── Shared JDBC URL (provided by embedded PG) ───────────────────────

    private val postgresJdbcUrl: String get() = PgEmbeddedTestSupport.jdbcUrl
    private val postgresUser: String get() = PgEmbeddedTestSupport.username
    private val postgresPassword: String get() = PgEmbeddedTestSupport.password

    // ── ApplicationContextRunner factory ────────────────────────────────

    private fun createJdbcRunner(vararg configClasses: Class<*>): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SovereignJdbcPersistenceAutoConfiguration::class.java,
                    SovereignTramaiAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(
                JdbcE2eDataSourceConfig::class.java,
                DemoProviderConfiguration::class.java,
                *configClasses,
            ).withPropertyValues(
                "tramai.sovereign.enabled=true",
                "tramai.sovereign.allowed-models[0]=local-invoice-model",
                "tramai.sovereign.allowed-providers[0]=deterministic-local-provider",
                "tramai.sovereign.provider-zones.deterministic-local-provider=LOCAL",
                "tramai.sovereign.models.local-invoice-model=deterministic-local-provider",
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )

    // ════════════════════════════════════════════════════════════════════
    // Test 1 — Spring Boot starts with JDBC stores
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `jdbc profile wires all sovereign stores from PostgreSQL-backed implementations`() {
        ensureMigrationsApplied()

        createJdbcRunner()
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalStore::class.java)
                assertThat(ctx.getBean(ApprovalStore::class.java))
                    .isExactlyInstanceOf(JdbcApprovalStore::class.java)

                assertThat(ctx).hasSingleBean(SuspendedInvocationStore::class.java)
                assertThat(ctx.getBean(SuspendedInvocationStore::class.java))
                    .isExactlyInstanceOf(JdbcSuspendedInvocationStore::class.java)

                assertThat(ctx).hasSingleBean(ApprovalContinuationStore::class.java)
                assertThat(ctx.getBean(ApprovalContinuationStore::class.java))
                    .isExactlyInstanceOf(JdbcApprovalContinuationStore::class.java)

                assertThat(ctx).hasSingleBean(AuditStore::class.java)
                assertThat(ctx.getBean(AuditStore::class.java))
                    .isExactlyInstanceOf(JdbcAuditStore::class.java)

                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxStore::class.java)
                assertThat(ctx.getBean(SovereignOpsAuditOutboxStore::class.java))
                    .isExactlyInstanceOf(
                        dev.tramai.spring.sovereign.persistence.jdbc.JdbcSovereignOpsAuditOutboxStore::class.java,
                    )

                // None should be in-memory stores
                assertThat(ctx.getBean(ApprovalStore::class.java))
                    .isNotInstanceOf(InMemoryApprovalStore::class.java)
                assertThat(ctx.getBean(ApprovalContinuationStore::class.java))
                    .isNotInstanceOf(InMemoryApprovalContinuationStore::class.java)
                assertThat(ctx.getBean(AuditStore::class.java))
                    .isNotInstanceOf(InMemoryAuditStore::class.java)
            }
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 2 — Persist state, restart, recover state (audit + outbox)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `jdbc sovereign state survives Spring context restart`() {
        ensureMigrationsApplied()

        val streamId = "e2e-stream-${UUID.randomUUID()}"
        val eventKey = "e2e-event-key-${UUID.randomUUID()}"

        // ── Context A: write audit events and an outbox record ────────
        lateinit var outboxId: String
        createJdbcRunner()
            .run { ctx ->
                val auditStore = ctx.getBean(AuditStore::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    // Append first audit event
                    auditStore.appendNext(streamId) { _: AuditEvent? ->
                        AuditEvent(
                            schemaVersion = 1,
                            hashAlgorithm = AuditHashAlgorithm.SHA_256,
                            auditStreamId = streamId,
                            eventId = UUID.randomUUID().toString(),
                            sequenceNumber = 1,
                            workflowRunId = "e2e-wf",
                            correlationId = "e2e-corr",
                            actor = "test-actor",
                            enforcementPoint = "e2e-test",
                            decision = "permit",
                            policyVersion = "1.0",
                            workflowDigest = "sha256:0000",
                            previousEventHash = null,
                            eventHash = "",
                            timestamp = Instant.now(),
                            reasonCode = null,
                        ).let { it.copy(eventHash = it.calculateHash()) }
                    }
                    // Append second audit event: latest is the first event
                    val firstEvent = auditStore.latestEvent(streamId)
                    auditStore.appendNext(streamId) { _: AuditEvent? ->
                        AuditEvent(
                            schemaVersion = 1,
                            hashAlgorithm = AuditHashAlgorithm.SHA_256,
                            auditStreamId = streamId,
                            eventId = UUID.randomUUID().toString(),
                            sequenceNumber = (firstEvent?.sequenceNumber ?: 0L) + 1L,
                            workflowRunId = "e2e-wf",
                            correlationId = "e2e-corr",
                            actor = "test-actor",
                            enforcementPoint = "e2e-test",
                            decision = "permit",
                            policyVersion = "1.0",
                            workflowDigest = "sha256:dummy",
                            previousEventHash = firstEvent?.eventHash,
                            eventHash = "",
                            timestamp = Instant.now(),
                            reasonCode = null,
                        ).let { it.copy(eventHash = it.calculateHash()) }
                    }

                    // Append an outbox record in PREPARED state — capture the outboxId
                    val appended =
                        outboxStore.append(
                            SovereignOpsAuditOutboxRecord(
                                aggregateIdDigest = sha256Hex("agg-1"),
                                eventKey = eventKey,
                                actor = "e2e-actor",
                                workflowRunId = "e2e-wf",
                                correlationId = "e2e-corr",
                                approvalStatus = "DENIED",
                                approvalVersion = 1,
                                reasonDigest = sha256Hex("reason"),
                                reasonLength = 6,
                            ),
                        )
                    outboxId = appended.outboxId
                }
            }

        // ── Context B: read back state from the same PostgreSQL ───────
        createJdbcRunner()
            .run { ctx ->
                val auditStore = ctx.getBean(AuditStore::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    // Read audit stream — should contain 2 events
                    val events = auditStore.readStream(streamId)
                    assertThat(events).hasSize(2)
                    assertThat(events[0].sequenceNumber).isEqualTo(1)
                    assertThat(events[1].sequenceNumber).isEqualTo(2)

                    // Verify hash chain continuity
                    assertThat(events[1].previousEventHash).isEqualTo(events[0].eventHash)

                    // Recover the exact outbox record by identity
                    val recovered = outboxStore.findByEventKey(eventKey)
                    assertThat(recovered).isNotNull
                    assertThat(recovered!!.outboxId).isEqualTo(outboxId)
                    assertThat(recovered.status).isEqualTo(SovereignOpsAuditOutboxStatus.PREPARED)
                }
            }
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 3 — No silent memory fallback
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `jdbc profile with unavailable database wires JDBC stores and fails on store operation`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SovereignJdbcPersistenceAutoConfiguration::class.java,
                    SovereignTramaiAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(
                DemoProviderConfiguration::class.java,
                AlwaysFailingDataSourceConfig::class.java,
            ).withPropertyValues(
                "tramai.sovereign.enabled=true",
                "tramai.sovereign.allowed-models[0]=local-invoice-model",
                "tramai.sovereign.allowed-providers[0]=deterministic-local-provider",
                "tramai.sovereign.provider-zones.deterministic-local-provider=LOCAL",
                "tramai.sovereign.models.local-invoice-model=deterministic-local-provider",
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            ).run { ctx ->
                // The app should NOT have failed at startup — JDBC stores wire
                // without contacting the database (schema existence is not checked eagerly).
                assertThat(ctx).hasNotFailed()

                // JDBC stores are wired, not in-memory
                val auditStore = ctx.getBean(AuditStore::class.java)
                assertThat(auditStore).isExactlyInstanceOf(JdbcAuditStore::class.java)
                assertThat(auditStore).isNotInstanceOf(InMemoryAuditStore::class.java)

                // Store operation fails with sanitized IllegalStateException
                // (does not silently succeed or leak raw SQLException)
                org.assertj.core.api.Assertions
                    .assertThatThrownBy {
                        runBlocking {
                            auditStore.readStream("unavailable-db-test")
                        }
                    }.isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("Database operation failed for audit stream")
            }
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 4 — Audit outbox dispatch survives restart
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `pending audit outbox record is claimable after restart`() {
        ensureMigrationsApplied()

        val eventKey = "e2e-restart-outbox-${UUID.randomUUID()}"

        // ── Context A: append and markReadyForDispatch ─────────────────
        createJdbcRunner()
            .run { ctx ->
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    // Append PREPARED record
                    val record =
                        SovereignOpsAuditOutboxRecord(
                            aggregateIdDigest = sha256Hex("agg-outbox-restart"),
                            eventKey = eventKey,
                            actor = "e2e-actor",
                            workflowRunId = "e2e-wf",
                            correlationId = "e2e-corr",
                            approvalStatus = "DENIED",
                            approvalVersion = 1,
                            reasonDigest = sha256Hex("reason"),
                            reasonLength = 6,
                        )
                    val appended = outboxStore.append(record)

                    // Mark ready for dispatch → PENDING
                    outboxStore.markReadyForDispatch(
                        appended.outboxId,
                        SovereignOpsAuditOutboxStatus.PREPARED,
                    )
                }
            }

        // ── Context B: claim and dispatch ──────────────────────────────
        createJdbcRunner()
            .run { ctx ->
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    // Claim pending records
                    val claimed =
                        outboxStore.claimPending(
                            claimedBy = "worker-B",
                            limit = 10,
                            now = Instant.now(),
                        )
                    assertThat(claimed).isNotEmpty
                    val claim = claimed.first { it.eventKey == eventKey }
                    assertThat(claim.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTING)
                    assertThat(claim.claimedBy).isEqualTo("worker-B")

                    // Complete the dispatch
                    val emitted =
                        outboxStore.markEmitted(
                            claim.outboxId,
                            SovereignOpsAuditOutboxStatus.EMITTING,
                            expectedAttemptCount = claim.attemptCount,
                            emittedAt = Instant.now(),
                        )
                    assertThat(emitted.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
                }
            }
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 5 — Audit stream survives restart and validates chain
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `audit stream remains tamper-evident after restart`() {
        ensureMigrationsApplied()

        val streamId = "e2e-audit-restart-${UUID.randomUUID()}"

        // ── Context A: append two audit events ────────────────────────
        createJdbcRunner()
            .run { ctx ->
                val auditStore = ctx.getBean(AuditStore::class.java)

                runBlocking {
                    // Event 1
                    auditStore.appendNext(streamId) { _: AuditEvent? ->
                        AuditEvent(
                            schemaVersion = 1,
                            hashAlgorithm = AuditHashAlgorithm.SHA_256,
                            auditStreamId = streamId,
                            eventId = UUID.randomUUID().toString(),
                            sequenceNumber = 1,
                            workflowRunId = "e2e-audit-wf",
                            correlationId = "e2e-audit-corr",
                            actor = "e2e-tester",
                            enforcementPoint = "e2e-test",
                            decision = "permit",
                            policyVersion = "1.0",
                            workflowDigest = "sha256:0000",
                            previousEventHash = null,
                            eventHash = "",
                            timestamp = Instant.now(),
                            reasonCode = null,
                        ).let { it.copy(eventHash = it.calculateHash()) }
                    }
                    // Event 2: capture the latest to chain
                    val firstEvent = auditStore.latestEvent(streamId)
                    auditStore.appendNext(streamId) { _: AuditEvent? ->
                        AuditEvent(
                            schemaVersion = 1,
                            hashAlgorithm = AuditHashAlgorithm.SHA_256,
                            auditStreamId = streamId,
                            eventId = UUID.randomUUID().toString(),
                            sequenceNumber = (firstEvent?.sequenceNumber ?: 0L) + 1L,
                            workflowRunId = "e2e-audit-wf",
                            correlationId = "e2e-audit-corr",
                            actor = "e2e-tester",
                            enforcementPoint = "e2e-test",
                            decision = "permit",
                            policyVersion = "1.0",
                            workflowDigest = "sha256:dummy",
                            previousEventHash = firstEvent?.eventHash,
                            eventHash = "",
                            timestamp = Instant.now(),
                            reasonCode = null,
                        ).let { it.copy(eventHash = it.calculateHash()) }
                    }
                }
            }

        // ── Context B: read stream and validate chain ─────────────────
        createJdbcRunner()
            .run { ctx ->
                val auditStore = ctx.getBean(AuditStore::class.java)

                runBlocking {
                    val events = auditStore.readStream(streamId)
                    assertThat(events).hasSize(2)

                    // Sequence ordering
                    assertThat(events[0].sequenceNumber).isEqualTo(1)
                    assertThat(events[1].sequenceNumber).isEqualTo(2)

                    // Hash chain linkage
                    assertThat(events[1].previousEventHash).isEqualTo(events[0].eventHash)

                    // Self-hash correctness
                    assertThat(events[0].eventHash).isEqualTo(
                        events[0].copy(eventHash = "").calculateHash(),
                    )
                    assertThat(events[1].eventHash).isEqualTo(
                        events[1].copy(eventHash = "").calculateHash(),
                    )

                    // Stream identity
                    assertThat(events).allMatch { it.auditStreamId == streamId }
                }
            }
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 6 — Two-worker lease coordination
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `two JDBC-backed workers coordinate through worker lease`() {
        ensureMigrationsApplied()

        val leaseName = "test-coordination"
        val now = Instant.now()
        val leaseDuration = Duration.ofSeconds(30)

        // ── Context A: worker-a acquires the lease ──────────────────────
        createJdbcRunner()
            .run { ctx ->
                val leaseStore = ctx.getBean(SovereignOpsWorkerLeaseStore::class.java)
                runBlocking {
                    val result = leaseStore.tryAcquire(leaseName, "worker-a", now, leaseDuration)
                    assertThat(result)
                        .isInstanceOf(SovereignOpsWorkerLeaseAcquisition.Acquired::class.java)
                    val acquired = result as SovereignOpsWorkerLeaseAcquisition.Acquired
                    assertThat(acquired.lease.ownerId).isEqualTo("worker-a")
                    assertThat(acquired.lease.isExpired(now)).isFalse()
                }
            }

        // ── Context B: worker-b attempts to acquire → HeldByOther ───────
        createJdbcRunner()
            .run { ctx ->
                val leaseStore = ctx.getBean(SovereignOpsWorkerLeaseStore::class.java)
                runBlocking {
                    val result = leaseStore.tryAcquire(leaseName, "worker-b", now, leaseDuration)
                    assertThat(result)
                        .isInstanceOf(SovereignOpsWorkerLeaseAcquisition.HeldByOther::class.java)
                    val held = result as SovereignOpsWorkerLeaseAcquisition.HeldByOther
                    assertThat(held.lease.ownerId).isEqualTo("worker-a")
                }
            }

        // ── Context A re-check: worker-a still owns the lease ───────────
        createJdbcRunner()
            .run { ctx ->
                val leaseStore = ctx.getBean(SovereignOpsWorkerLeaseStore::class.java)
                runBlocking {
                    val lease = leaseStore.get(leaseName)
                    assertThat(lease).isNotNull
                    assertThat(lease!!.ownerId).isEqualTo("worker-a")
                    assertThat(lease.isExpired(now)).isFalse()
                }
            }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * No-op: migrations are applied once by [PgEmbeddedTestSupport.start].
     * Kept for call-site compatibility.
     */
    private fun ensureMigrationsApplied() {
        // Migrations already applied by PgEmbeddedTestSupport.start()
    }

    private fun createDataSource(): DataSource {
        val ds = HikariDataSource()
        ds.jdbcUrl = postgresJdbcUrl
        ds.username = postgresUser
        ds.password = postgresPassword
        ds.maximumPoolSize = 5
        return ds
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ════════════════════════════════════════════════════════════════════
    // Inner configuration classes (isolated to this test)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Provides a [DataSource] bean pointing to the shared embedded PostgreSQL.
     */
    @Configuration
    class JdbcE2eDataSourceConfig {
        @Bean(destroyMethod = "close")
        fun e2eDataSource(): DataSource {
            val ds = HikariDataSource()
            ds.jdbcUrl = PgEmbeddedTestSupport.jdbcUrl
            ds.username = PgEmbeddedTestSupport.username
            ds.password = PgEmbeddedTestSupport.password
            ds.maximumPoolSize = 3
            return ds
        }
    }

    /**
     * Provides a [DataSource] whose every operation throws [java.sql.SQLException].
     * Used to prove that `type=jdbc` does not silently fall back to in-memory stores
     * when the database is unreachable — the store operation itself fails instead.
     *
     * This deliberately avoids PostgreSQL-like connection strings so GitGuardian
     * and similar secret scanners do not flag it as a leaked credential.
     */
    @Configuration
    class AlwaysFailingDataSourceConfig {
        @Bean
        fun alwaysFailingDataSource(): DataSource {
            val failure = java.sql.SQLException("Simulated database unavailable for no-fallback test")
            return object : javax.sql.DataSource {
                override fun getConnection() = throw failure

                override fun getConnection(
                    unused: String?,
                    unused2: String?,
                ) = throw failure

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> unwrap(iface: Class<T>): T = throw failure

                override fun isWrapperFor(unused: Class<*>?) = false

                override fun getLogWriter() = null

                override fun setLogWriter(unused: java.io.PrintWriter?) {}

                override fun getLoginTimeout() = 0

                override fun setLoginTimeout(unused: Int) {}

                override fun getParentLogger() = throw java.sql.SQLFeatureNotSupportedException()
            }
        }
    }
}
