package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlaneAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalGatewayAutoConfiguration
import dev.tramai.spring.sovereign.ops.OperatorApprovalPolicy
import dev.tramai.spring.sovereign.ops.OperatorAuthority
import dev.tramai.spring.sovereign.ops.SovereignOpsApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 0.7.6g2 Adversarial Concurrency Qualification Suite (G2-08, G2-09).
 *
 * Proves that against a real PostgreSQL store with optimistic locking:
 * - G2-08: Concurrent Approve vs Deny released at a CyclicBarrier results in exactly
 *   one winning committed decision and exactly one Stale result, with exactly one
 *   audit outbox record and terminal approval status.
 * - G2-09: Repeated concurrent identical decisions (5 threads) released at a CyclicBarrier
 *   result in exactly one committed decision and 4 Stale results, with zero duplicate outbox records.
 */
@SpringBootTest(classes = [PrivilegedControlBoundaryConcurrencyQualificationTest.TestConfig::class])
@Tag("jdbc")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivilegedControlBoundaryConcurrencyQualificationTest {
    @Autowired
    private lateinit var approvalStore: ApprovalStore

    @Autowired
    private lateinit var mutationStore: SovereignOpsApprovalMutationStore

    @Autowired
    private lateinit var outboxStore: SovereignOpsAuditOutboxStore

    @Autowired
    private lateinit var gateway: ApprovalGateway

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-25T10:15:30Z"), ZoneOffset.UTC)

    private val approverAuthority =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "approver-alice",
                issuer = "https://issuer.example",
                authenticatedAt = Instant.parse("2026-10-25T09:00:00Z"),
                capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                approverRoles = setOf(ApproverRole("medical-reviewer")),
            ),
        )

    private val denierAuthority =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "denier-bob",
                issuer = "https://issuer.example",
                authenticatedAt = Instant.parse("2026-10-25T09:00:00Z"),
                capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                approverRoles = setOf(ApproverRole("medical-reviewer")),
            ),
        )

    private val approvalPolicy =
        OperatorApprovalPolicy(mapOf("v1" to setOf(ApproverRole("medical-reviewer"))))

    @BeforeAll
    fun runMigrations() {
        listOf(
            "tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql",
            "tramai/persistence/jdbc/postgres/V2__approval_continuations.sql",
            "tramai/persistence/jdbc/postgres/V4__audit_outbox_hardening.sql",
            "tramai/persistence/jdbc/postgres/V6__approval_resume_credential_custody.sql",
            "tramai/persistence/jdbc/postgres/V10__approval_actor_provenance.sql",
        ).forEach { resource ->
            val sql =
                javaClass.classLoader
                    .getResourceAsStream(resource)
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("Migration not found: $resource")
            jdbcTemplate.execute(sql)
        }
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE approval_continuations, suspended_invocations, audit_outbox, approvals CASCADE",
        )
    }

    private suspend fun suspendApproval(
        subject: String,
        wfRunId: String,
    ): ApprovalId {
        val requestResult =
            gateway.requestApproval(
                subject = ApprovalSubject(subject),
                recommendation =
                    ApprovalRecommendation(
                        type = "claim-review",
                        summary = "concurrency qualification test",
                        payload = mapOf("amount" to "1000"),
                    ),
                requiredRole = ApproverRole("medical-reviewer"),
                workflowRunId = WorkflowRunId(wfRunId),
            )
        return (requestResult as ApprovalRequestResult.Suspended).approvalId
    }

    private fun createControlPlane() =
        SovereignOpsApprovalDecisionControlPlane(
            approvalPolicy = approvalPolicy,
            approvalStore = approvalStore,
            mutationStore = mutationStore,
            clock = clock,
        )

    @Test
    fun `G2-08 - concurrent approve and deny at barrier commit exactly one decision and report one stale`() {
        runBlocking {
            val approvalId = suspendApproval("concurrent-adv-g208", "wf-adv-g208")
            val controlPlane = createControlPlane()

            val approveCmd = ApprovalDecisionCommand(approvalId, 0L, "approved-at-barrier", "corr-race-approve")
            val denyCmd = ApprovalDecisionCommand(approvalId, 0L, "denied-at-barrier", "corr-race-deny")

            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val approveFuture =
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            runBlocking { controlPlane.approve(approveCmd, approverAuthority) }
                        },
                    )
                val denyFuture =
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            runBlocking { controlPlane.deny(denyCmd, denierAuthority) }
                        },
                    )

                val results = listOf(approveFuture.get(30, TimeUnit.SECONDS), denyFuture.get(30, TimeUnit.SECONDS))
                val committed =
                    results.filter { it is ApprovalDecisionResult.Approved || it is ApprovalDecisionResult.Denied }
                val stale = results.filterIsInstance<ApprovalDecisionResult.Stale>()

                assertThat(committed).describedAs("exactly one committed decision").hasSize(1)
                assertThat(stale).describedAs("exactly one stale result").hasSize(1)
                assertThat(stale.single().expectedVersion).isZero()
                assertThat(stale.single().currentVersion).isEqualTo(1L)

                // Inspect authoritative database row
                val row =
                    jdbcTemplate.queryForMap(
                        "SELECT status, version, decision_actor_hash FROM approvals WHERE approval_id = ?",
                        approvalId.value,
                    )
                assertThat((row["version"] as Number).toLong()).isEqualTo(1L)
                val finalStatus = row["status"] as String
                assertThat(finalStatus).isIn("APPROVED", "DENIED")
                assertThat(row["decision_actor_hash"]).isNotNull()

                // Outbox contains exactly one PENDING record, zero PREPARED records
                val pendingOutbox = outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)
                assertThat(pendingOutbox).hasSize(1)
                assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, 10)).isEmpty()
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `G2-09 - five concurrent identical approve commands commit exactly once and report four stale`() {
        runBlocking {
            val approvalId = suspendApproval("concurrent-adv-g209", "wf-adv-g209")
            val controlPlane = createControlPlane()

            val concurrency = 5
            val barrier = CyclicBarrier(concurrency)
            val pool = Executors.newFixedThreadPool(concurrency)
            try {
                val futures =
                    (1..concurrency).map { idx ->
                        pool.submit(
                            Callable {
                                barrier.await(10, TimeUnit.SECONDS)
                                val cmd =
                                    ApprovalDecisionCommand(
                                        approvalId,
                                        0L,
                                        "concurrent-approval-$idx",
                                        "corr-concurrent-$idx",
                                    )
                                runBlocking { controlPlane.approve(cmd, approverAuthority) }
                            },
                        )
                    }

                val results = futures.map { it.get(30, TimeUnit.SECONDS) }
                val committed = results.filterIsInstance<ApprovalDecisionResult.Approved>()
                val stale = results.filterIsInstance<ApprovalDecisionResult.Stale>()

                assertThat(committed)
                    .describedAs("exactly one committed decision among 5 concurrent attempts")
                    .hasSize(1)
                assertThat(stale).describedAs("exactly 4 losing commands reported stale").hasSize(concurrency - 1)
                stale.forEach {
                    assertThat(it.expectedVersion).isZero()
                    assertThat(it.currentVersion).isEqualTo(1L)
                }

                // Inspect authoritative database row
                val row =
                    jdbcTemplate.queryForMap(
                        "SELECT status, version, decision_actor_hash FROM approvals WHERE approval_id = ?",
                        approvalId.value,
                    )
                assertThat((row["version"] as Number).toLong()).isEqualTo(1L)
                assertThat(row["status"]).isEqualTo("APPROVED")
                assertThat(row["decision_actor_hash"]).isEqualTo("sha256:${sha256Hex(approverAuthority.subject)}")

                // Outbox contains exactly one PENDING record, zero duplicate outbox records
                val pendingOutbox = outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)
                assertThat(pendingOutbox).hasSize(1)
                assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, 10)).isEmpty()
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `G2-12 - outbox failure in PostgreSQL rolls back mutation transaction and outbox`() {
        runBlocking {
            val approvalId = suspendApproval("rollback-adv-g212", "wf-adv-g212")
            val controlPlane = createControlPlane()

            installFailingOutboxUpdateTrigger()
            try {
                val cmd = ApprovalDecisionCommand(approvalId, 0L, "should-rollback", "corr-rollback-test")
                val result = controlPlane.approve(cmd, approverAuthority)

                assertThat(result).isInstanceOf(ApprovalDecisionResult.Conflict::class.java)
                val conflict = result as ApprovalDecisionResult.Conflict
                assertThat(conflict.reason).contains("tramai-sovereign-ops-approval-mutation-database-failure")

                // Authoritative approval row in PostgreSQL: MUST BE UNMUTATED (PENDING, version 0, no decider)
                val row =
                    jdbcTemplate.queryForMap(
                        "SELECT status, version, decision_actor_hash, decided_at FROM approvals WHERE approval_id = ?",
                        approvalId.value,
                    )
                assertThat(row["status"]).isEqualTo("PENDING")
                assertThat((row["version"] as Number).toLong()).isZero()
                assertThat(row["decision_actor_hash"]).isNull()
                assertThat(row["decided_at"]).isNull()

                val persisted = requireNotNull(approvalStore.get(approvalId.value))
                assertThat(persisted.status).isEqualTo(ApprovalStatus.PENDING)
                assertThat(persisted.version).isZero()
                assertThat(persisted.decidedBy).isNull()
                assertThat(persisted.decidedAt).isNull()

                // Authoritative audit_outbox in PostgreSQL: MUST BE EMPTY (rolled back)
                val outboxCount =
                    jdbcTemplate.queryForObject("SELECT count(*) FROM audit_outbox", Long::class.java) ?: 0L
                assertThat(outboxCount).isZero()
            } finally {
                dropFailingOutboxUpdateTrigger()
            }
        }
    }

    private fun installFailingOutboxUpdateTrigger() {
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION fail_audit_outbox_update() RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'injected-outbox-storage-disk-failure';
            END;
            $$ LANGUAGE plpgsql;

            CREATE TRIGGER trg_fail_outbox_update
            BEFORE UPDATE ON audit_outbox
            FOR EACH ROW EXECUTE FUNCTION fail_audit_outbox_update();
            """.trimIndent(),
        )
    }

    private fun dropFailingOutboxUpdateTrigger() {
        jdbcTemplate.execute(
            """
            DROP TRIGGER IF EXISTS trg_fail_outbox_update ON audit_outbox;
            DROP FUNCTION IF EXISTS fail_audit_outbox_update();
            """.trimIndent(),
        )
    }

    companion object {
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("sovereign_ops_concurrency_adv_test")
                .withUsername("test")
                .withPassword("test")
                .apply { start() }

        private val keyFile =
            Files.createTempFile("tramai-sovereign-concurrency-key", ".b64").apply {
                toFile().writeText(
                    Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
                )
                toFile().deleteOnExit()
            }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("tramai.sovereign.persistence.type") { "jdbc" }
            registry.add("tramai.sovereign.persistence.encryption.key-file") { keyFile.toAbsolutePath().toString() }
            registry.add("tramai.sovereign.ops.mutations-enabled") { "true" }
        }

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }

    @SpringBootConfiguration
    open class DataSourceConfig {
        @Bean
        open fun dataSource(
            @Value("\${spring.datasource.url}") url: String,
            @Value("\${spring.datasource.username}") username: String,
            @Value("\${spring.datasource.password}") password: String,
        ): DataSource =
            PGSimpleDataSource().apply {
                setUrl(url)
                setUser(username)
                setPassword(password)
            }

        @Bean
        open fun approvalGatewayRequestFactory(): ApprovalGatewayRequestFactory = TestApprovalGatewayRequestFactory()

        @Bean
        open fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)
    }

    @SpringBootConfiguration
    @Import(
        DataSourceConfig::class,
        SovereignJdbcPersistenceAutoConfiguration::class,
        ApprovalGatewayAutoConfiguration::class,
        ApprovalDecisionControlPlaneAutoConfiguration::class,
    )
    open class TestConfig

    private class TestApprovalGatewayRequestFactory : ApprovalGatewayRequestFactory {
        override suspend fun createRequest(
            subject: ApprovalSubject,
            recommendation: ApprovalRecommendation,
            requiredRole: ApproverRole,
            workflowRunId: WorkflowRunId?,
        ): ApprovalGatewayPersistenceRequest {
            val now = Instant.now()
            val approvalId = subject.value
            val wfRunId = workflowRunId?.value ?: "wf-$approvalId"
            val argumentsDigest = Sha256Digest.of("sha256:${sha256Hex("arguments:$approvalId")}")
            val workflowDigest = Sha256Digest.of("sha256:${sha256Hex("workflow:$approvalId")}")
            val tokenDigest = Sha256Digest.of("sha256:${sha256Hex("token:$approvalId")}")
            val binding =
                ApprovalBinding(
                    workflowRunId = wfRunId,
                    toolName = "claim-triage",
                    argumentsDigest = argumentsDigest,
                    policyVersion = "v1",
                    workflowDigest = workflowDigest,
                    approvalTokenDigest = tokenDigest,
                )

            return ApprovalGatewayPersistenceRequest(
                approvalRequest = buildApprovalRequest(approvalId, binding, now),
                continuation = buildContinuation(approvalId, binding, now),
                sensitiveArguments =
                    SensitiveToolArguments.of(
                        """{"subject":"${subject.value}","summary":"${recommendation.summary}"}""",
                    ),
                suspendedInvocationMetadata =
                    buildSuspendedMetadata(approvalId, wfRunId, argumentsDigest, workflowDigest),
                replayEnvelope = SensitiveReplayEnvelope.of(emptyList<dev.tramai.core.model.Message>()),
                resumeToken = ResumeToken("resume-$approvalId"),
            )
        }

        private fun buildApprovalRequest(
            approvalId: String,
            binding: ApprovalBinding,
            now: Instant,
        ) = ApprovalRequest(
            approvalId = approvalId,
            binding = binding,
            status = ApprovalStatus.PENDING,
            requestedBy = "test-requester",
            requestedAt = now,
            expiresAt = now.plusSeconds(600),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        private fun buildContinuation(
            approvalId: String,
            binding: ApprovalBinding,
            now: Instant,
        ) = ApprovalContinuation(
            approvalId = approvalId,
            workflowRunId = binding.workflowRunId,
            correlationId = "corr-$approvalId",
            toolCallId = "tool-$approvalId",
            toolName = binding.toolName,
            argumentsDigest = binding.argumentsDigest,
            policyVersion = binding.policyVersion,
            workflowDigest = binding.workflowDigest,
            status = ApprovalContinuationStatus.PENDING,
            createdAt = now,
            approvalExpiresAt = now.plusSeconds(600),
            claimedBy = null,
            claimedAt = null,
            completedAt = null,
            version = 0L,
        )

        private fun buildSuspendedMetadata(
            approvalId: String,
            wfRunId: String,
            argumentsDigest: Sha256Digest,
            workflowDigest: Sha256Digest,
        ) = SuspendedInvocationMetadata(
            approvalId = approvalId,
            toolCallId = "tool-$approvalId",
            toolName = "claim-triage",
            toolCallIndex = 0,
            correlationId = "corr-$approvalId",
            identity =
                EngineExecutionIdentity(
                    workflowRunId = wfRunId,
                    correlationId = "corr-$approvalId",
                    workflowDigest = workflowDigest,
                    policyVersion = "v1",
                    actorId = "test-requester",
                ),
            securityContext = ExecutionSecurityContext(),
            operationReference =
                ResumeOperationReference(
                    serviceInterface = "dev.tramai.test.Workflow",
                    methodName = "triage",
                    jvmMethodDescriptor = "(Ljava/lang/String;)V",
                    resumeDefinitionDigest = argumentsDigest,
                ),
            replayEnvelopeDigest =
                dev.tramai.engine.ReplayEnvelopeDigestHelper.compute(
                    ResumeOperationReference(
                        serviceInterface = "dev.tramai.test.Workflow",
                        methodName = "triage",
                        jvmMethodDescriptor = "(Ljava/lang/String;)V",
                        resumeDefinitionDigest = argumentsDigest,
                    ),
                    emptyList(),
                ),
            toolReference = ResumeToolReference("claim-triage", argumentsDigest),
        )
    }
}
