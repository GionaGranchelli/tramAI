package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.spring.sovereign.ops.AllowAllApprovalDecisionAuthorizer
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlaneAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalGatewayAutoConfiguration
import dev.tramai.spring.sovereign.ops.SovereignOpsApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * JDBC-level test proving approve/deny control-plane behavior at the store boundary.
 *
 * Verifies that approving or denying pending approvals through the control plane
 * creates PENDING audit outbox records with correct status, version, and unique event keys.
 */
@SpringBootTest(
    classes = [JdbcSovereignOpsTestConfig::class],
)
@JdbcTestTag
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsApprovalDecisionControlPlaneTest {
    @Autowired
    private lateinit var approvalStore: dev.tramai.core.approval.ApprovalStore

    @Autowired
    private lateinit var mutationStore: SovereignOpsApprovalMutationStore

    @Autowired
    private lateinit var outboxStore: dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore

    @Autowired
    private lateinit var gateway: ApprovalGateway

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-25T10:15:30Z"), ZoneOffset.UTC)

    @BeforeAll
    fun runMigrations() {
        listOf(
            "tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql",
            "tramai/persistence/jdbc/postgres/V2__approval_continuations.sql",
            "tramai/persistence/jdbc/postgres/V4__audit_outbox_hardening.sql",
            "tramai/persistence/jdbc/postgres/V6__approval_resume_credential_custody.sql",
        ).forEach { resource ->
            val sql =
                javaClass.classLoader
                    .getResourceAsStream(resource)
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("Migration not found: $resource")
            runCatching { jdbcTemplate.execute(sql) }
        }
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE approval_continuations, suspended_invocations, audit_outbox, approvals CASCADE")
    }

    @Test
    fun `approve approval creates complete decision evidence record`() {
        runBlocking {
            val requiredRole = ApproverRole("medical-reviewer")
            val requestResult =
                gateway.requestApproval(
                    subject = ApprovalSubject("test-approve-evidence"),
                    recommendation =
                        ApprovalRecommendation(
                            type = "claim-review",
                            summary = "high-value claim",
                            payload = mapOf("amount" to "5000"),
                        ),
                    requiredRole = requiredRole,
                    workflowRunId = WorkflowRunId("wf-test-approve-evidence"),
                )
            val approvalId = (requestResult as ApprovalRequestResult.Suspended).approvalId

            val controlPlane: ApprovalDecisionControlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = approvalStore,
                    mutationStore = mutationStore,
                    authorizer = AllowAllApprovalDecisionAuthorizer,
                    clock = clock,
                )

            val result =
                controlPlane.approve(
                    decisionCommand(
                        approvalId,
                        0L,
                        requiredRole,
                        "Medical necessity established",
                        "corr-approve-evidence",
                    ),
                )

            assertThat(result).isInstanceOf(ApprovalDecisionResult.Approved::class.java)
            val approved = result as ApprovalDecisionResult.Approved
            assertThat(approved.approvalId.value).isEqualTo(approvalId.value)
            assertThat(approved.decidedBy).isEqualTo("medical-ops-reviewer")

            val approval = approvalStore.get(approvalId.value)
            assertThat(approval).isNotNull
            assertThat(approval!!.status).isEqualTo(ApprovalStatus.APPROVED)
            assertThat(approval.version).isEqualTo(1L)

            val approvalOutbox = outboxStore.findByEventKey("approval-approved.${approvalId.value}")
            assertThat(approvalOutbox).isNotNull
            // Evidence structure
            assertThat(approvalOutbox!!.aggregateType).isEqualTo("approval")
            assertThat(approvalOutbox.operation).isEqualTo("approval-approved.${approvalId.value}")
            assertThat(approvalOutbox.eventKey).isEqualTo("approval-approved.${approvalId.value}")
            assertThat(approvalOutbox.actor).isEqualTo("medical-ops-reviewer")
            assertThat(approvalOutbox.workflowRunId).isEqualTo("wf-test-approve-evidence")
            assertThat(approvalOutbox.correlationId).isEqualTo("corr-approve-evidence")
            assertThat(approvalOutbox.approvalStatus).isEqualTo(ApprovalStatus.APPROVED.name)
            assertThat(approvalOutbox.approvalVersion).isEqualTo(1L)
            assertThat(approvalOutbox.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            // Digest shape — evidence is sanitised, not raw text
            assertThat(approvalOutbox.aggregateIdDigest).startsWith("sha256:")
            assertThat(approvalOutbox.aggregateIdDigest).hasSize(71)
            assertThat(approvalOutbox.reasonDigest).startsWith("sha256:")
            assertThat(approvalOutbox.reasonDigest).hasSize(71)
            assertThat(approvalOutbox.reasonLength).isEqualTo("Medical necessity established".length)
        }
    }

    @Test
    fun `deny approval creates complete decision evidence record`() {
        runBlocking {
            val requiredRole = ApproverRole("medical-reviewer")
            val requestResult =
                gateway.requestApproval(
                    subject = ApprovalSubject("test-deny-evidence"),
                    recommendation =
                        ApprovalRecommendation(
                            type = "claim-review",
                            summary = "insufficient evidence",
                        ),
                    requiredRole = requiredRole,
                    workflowRunId = WorkflowRunId("wf-test-deny-evidence"),
                )
            val approvalId = (requestResult as ApprovalRequestResult.Suspended).approvalId

            val controlPlane: ApprovalDecisionControlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = approvalStore,
                    mutationStore = mutationStore,
                    authorizer = AllowAllApprovalDecisionAuthorizer,
                    clock = clock,
                )

            val result =
                controlPlane.deny(
                    decisionCommand(
                        approvalId,
                        0L,
                        requiredRole,
                        "Denied because evidence is incomplete",
                        "corr-deny-evidence",
                    ),
                )

            assertThat(result).isInstanceOf(ApprovalDecisionResult.Denied::class.java)
            val denied = result as ApprovalDecisionResult.Denied
            assertThat(denied.approvalId.value).isEqualTo(approvalId.value)
            assertThat(denied.decidedBy).isEqualTo("medical-ops-reviewer")

            val approval = approvalStore.get(approvalId.value)
            assertThat(approval).isNotNull
            assertThat(approval!!.status).isEqualTo(ApprovalStatus.DENIED)
            assertThat(approval.version).isEqualTo(1L)

            val denialOutbox = outboxStore.findByEventKey("approval-denied.${approvalId.value}")
            assertThat(denialOutbox).isNotNull
            // Evidence structure
            assertThat(denialOutbox!!.aggregateType).isEqualTo("approval")
            assertThat(denialOutbox.operation).isEqualTo("approval-denied.${approvalId.value}")
            assertThat(denialOutbox.eventKey).isEqualTo("approval-denied.${approvalId.value}")
            assertThat(denialOutbox.actor).isEqualTo("medical-ops-reviewer")
            assertThat(denialOutbox.workflowRunId).isEqualTo("wf-test-deny-evidence")
            assertThat(denialOutbox.correlationId).isEqualTo("corr-deny-evidence")
            assertThat(denialOutbox.approvalStatus).isEqualTo(ApprovalStatus.DENIED.name)
            assertThat(denialOutbox.approvalVersion).isEqualTo(1L)
            assertThat(denialOutbox.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            // Digest shape — evidence is sanitised, not raw text
            assertThat(denialOutbox.aggregateIdDigest).startsWith("sha256:")
            assertThat(denialOutbox.aggregateIdDigest).hasSize(71)
            assertThat(denialOutbox.reasonDigest).startsWith("sha256:")
            assertThat(denialOutbox.reasonDigest).hasSize(71)
            assertThat(denialOutbox.reasonLength).isEqualTo("Denied because evidence is incomplete".length)
        }
    }

    @Test
    fun `decision evidence stores digest and length not raw comment text`() {
        runBlocking {
            val requiredRole = ApproverRole("medical-reviewer")
            val requestResult =
                gateway.requestApproval(
                    subject = ApprovalSubject("test-sanitised-evidence"),
                    recommendation =
                        ApprovalRecommendation(
                            type = "claim-review",
                            summary = "sensitive claim",
                        ),
                    requiredRole = requiredRole,
                    workflowRunId = WorkflowRunId("wf-test-sanitised-evidence"),
                )
            val approvalId = (requestResult as ApprovalRequestResult.Suspended).approvalId

            val controlPlane: ApprovalDecisionControlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = approvalStore,
                    mutationStore = mutationStore,
                    authorizer = AllowAllApprovalDecisionAuthorizer,
                    clock = clock,
                )

            val rawComment = "This claim contains sensitive medical details"
            controlPlane.approve(
                decisionCommand(approvalId, 0L, requiredRole, rawComment, "corr-sanitised").copy(actorId = "reviewer"),
            )

            val outbox = outboxStore.findByEventKey("approval-approved.${approvalId.value}")
            assertThat(outbox).isNotNull
            // The outbox stores a digest, not the raw comment
            assertThat(outbox!!.reasonDigest).isNotEqualTo(rawComment)
            assertThat(outbox.reasonDigest).startsWith("sha256:")
            assertThat(outbox.reasonLength).isEqualTo(rawComment.length)
            // The raw comment is not present anywhere in the outbox record fields
            assertThat(outbox.aggregateIdDigest).doesNotContain(rawComment)
            assertThat(outbox.operation).doesNotContain(rawComment)
        }
    }

    @Test
    fun `repeat approve returns AlreadyApproved without creating duplicate evidence`() {
        runBlocking {
            val requiredRole = ApproverRole("medical-reviewer")
            val requestResult =
                gateway.requestApproval(
                    subject = ApprovalSubject("test-repeat-approve"),
                    recommendation =
                        ApprovalRecommendation(
                            type = "claim-review",
                            summary = "repeat test",
                        ),
                    requiredRole = requiredRole,
                    workflowRunId = WorkflowRunId("wf-test-repeat-approve"),
                )
            val approvalId = (requestResult as ApprovalRequestResult.Suspended).approvalId

            val controlPlane: ApprovalDecisionControlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = approvalStore,
                    mutationStore = mutationStore,
                    authorizer = AllowAllApprovalDecisionAuthorizer,
                    clock = clock,
                )

            // First approve — creates evidence
            val firstResult =
                controlPlane.approve(
                    decisionCommand(approvalId, 0L, requiredRole, "Approved").copy(actorId = "reviewer-1"),
                )
            assertThat(firstResult).isInstanceOf(ApprovalDecisionResult.Approved::class.java)

            val firstOutbox = outboxStore.findByEventKey("approval-approved.${approvalId.value}")
            assertThat(firstOutbox).isNotNull

            // Second approve — returns AlreadyApproved, no duplicate evidence
            val secondResult =
                controlPlane.approve(
                    decisionCommand(approvalId, 1L, requiredRole, "Approved again").copy(actorId = "reviewer-2"),
                )
            assertThat(secondResult).isInstanceOf(ApprovalDecisionResult.AlreadyApproved::class.java)

            // get() the same outbox — still the single original record, key uniqueness enforced by store
            val secondOutbox = outboxStore.findByEventKey("approval-approved.${approvalId.value}")
            assertThat(secondOutbox).isNotNull
            // actor unchanged — no new evidence was created
            assertThat(secondOutbox!!.actor).isEqualTo("reviewer-1")
            assertThat(secondOutbox.approvalVersion).isEqualTo(1L)
        }
    }

    @Test
    fun `repeat deny returns AlreadyDenied without creating duplicate evidence`() {
        runBlocking {
            val requiredRole = ApproverRole("medical-reviewer")
            val requestResult =
                gateway.requestApproval(
                    subject = ApprovalSubject("test-repeat-deny"),
                    recommendation =
                        ApprovalRecommendation(
                            type = "claim-review",
                            summary = "repeat deny test",
                        ),
                    requiredRole = requiredRole,
                    workflowRunId = WorkflowRunId("wf-test-repeat-deny"),
                )
            val approvalId = (requestResult as ApprovalRequestResult.Suspended).approvalId

            val controlPlane: ApprovalDecisionControlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = approvalStore,
                    mutationStore = mutationStore,
                    authorizer = AllowAllApprovalDecisionAuthorizer,
                    clock = clock,
                )

            // First deny — creates evidence
            val firstResult =
                controlPlane.deny(
                    decisionCommand(approvalId, 0L, requiredRole, "Denied").copy(actorId = "reviewer-1"),
                )
            assertThat(firstResult).isInstanceOf(ApprovalDecisionResult.Denied::class.java)

            val firstOutbox = outboxStore.findByEventKey("approval-denied.${approvalId.value}")
            assertThat(firstOutbox).isNotNull

            // Second deny — returns AlreadyDenied, no duplicate evidence
            val secondResult =
                controlPlane.deny(
                    decisionCommand(approvalId, 1L, requiredRole, "Denied again").copy(actorId = "reviewer-2"),
                )
            assertThat(secondResult).isInstanceOf(ApprovalDecisionResult.AlreadyDenied::class.java)

            // get() the same outbox — still the single original record
            val secondOutbox = outboxStore.findByEventKey("approval-denied.${approvalId.value}")
            assertThat(secondOutbox).isNotNull
            // actor unchanged — no new evidence was created
            assertThat(secondOutbox!!.actor).isEqualTo("reviewer-1")
            assertThat(secondOutbox.approvalVersion).isEqualTo(1L)

            // Direct DB proof: only one evidence row exists across both deny attempts
            val pendingOutboxCount =
                jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM audit_outbox
                    WHERE event_key LIKE 'approval-denied.%'
                      AND status = 'PENDING'
                    """.trimIndent(),
                    Long::class.java,
                )
            assertThat(pendingOutboxCount).isOne
        }
    }

    @Test
    fun `can deny two different approvals without audit outbox event key conflict`() {
        runBlocking {
            val requiredRole = ApproverRole("medical-reviewer")

            val result1 =
                gateway.requestApproval(
                    subject = ApprovalSubject("claim-deny-conflict-1"),
                    recommendation =
                        ApprovalRecommendation(
                            type = "claim-review",
                            summary = "review required",
                        ),
                    requiredRole = requiredRole,
                    workflowRunId = WorkflowRunId("wf-deny-conflict-1"),
                )
            val result2 =
                gateway.requestApproval(
                    subject = ApprovalSubject("claim-deny-conflict-2"),
                    recommendation =
                        ApprovalRecommendation(
                            type = "claim-review",
                            summary = "review required",
                        ),
                    requiredRole = requiredRole,
                    workflowRunId = WorkflowRunId("wf-deny-conflict-2"),
                )
            val id1 = (result1 as ApprovalRequestResult.Suspended).approvalId
            val id2 = (result2 as ApprovalRequestResult.Suspended).approvalId

            val controlPlane: ApprovalDecisionControlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = approvalStore,
                    mutationStore = mutationStore,
                    authorizer = AllowAllApprovalDecisionAuthorizer,
                    clock = clock,
                )

            val deny1 =
                controlPlane.deny(
                    decisionCommand(id1, 0L, requiredRole, correlationId = "c1").copy(actorId = "reviewer"),
                )
            val deny2 =
                controlPlane.deny(
                    decisionCommand(id2, 0L, requiredRole, correlationId = "c2").copy(actorId = "reviewer"),
                )

            assertThat(deny1).isInstanceOf(ApprovalDecisionResult.Denied::class.java)
            assertThat(deny2).isInstanceOf(ApprovalDecisionResult.Denied::class.java)

            val outbox1 = outboxStore.findByEventKey("approval-denied.${id1.value}")
            val outbox2 = outboxStore.findByEventKey("approval-denied.${id2.value}")
            assertThat(outbox1).isNotNull
            assertThat(outbox2).isNotNull
            // The correlation id must reach the outbox unchanged, and with no comment the reason
            // digest/length keep their event-key derivation (a comment would change both).
            assertThat(outbox1!!.correlationId).isEqualTo("c1")
            assertThat(outbox2!!.correlationId).isEqualTo("c2")
            assertThat(outbox1.reasonLength).isEqualTo(outbox1.eventKey.length)
            assertThat(outbox1.reasonDigest).isEqualTo("sha256:${sha256Hex(outbox1.eventKey)}")
            assertThat(outbox2.reasonLength).isEqualTo(outbox2.eventKey.length)
            assertThat(outbox2.reasonDigest).isEqualTo("sha256:${sha256Hex(outbox2.eventKey)}")
        }
    }

    @Test
    fun `a stale observed version leaves the database and the outbox untouched`() {
        runBlocking {
            val requiredRole = ApproverRole("medical-reviewer")
            val requestResult =
                gateway.requestApproval(
                    subject = ApprovalSubject("test-stale-precondition"),
                    recommendation =
                        ApprovalRecommendation(
                            type = "claim-review",
                            summary = "stale attempt",
                            payload = mapOf("amount" to "100"),
                        ),
                    requiredRole = requiredRole,
                    workflowRunId = WorkflowRunId("wf-test-stale-precondition"),
                )
            val approvalId = (requestResult as ApprovalRequestResult.Suspended).approvalId
            val controlPlane: ApprovalDecisionControlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = approvalStore,
                    mutationStore = mutationStore,
                    authorizer = AllowAllApprovalDecisionAuthorizer,
                    clock = clock,
                )
            val decided =
                controlPlane.approve(
                    decisionCommand(approvalId, 0L, requiredRole, "approved once", "corr-stale-1"),
                )
            assertThat(decided).isInstanceOf(ApprovalDecisionResult.Approved::class.java)

            val stale =
                controlPlane.deny(
                    decisionCommand(approvalId, 0L, requiredRole, "stale deny", "corr-stale-2"),
                )

            assertThat(stale).isEqualTo(
                ApprovalDecisionResult.Stale(
                    approvalId = approvalId,
                    expectedVersion = 0L,
                    currentVersion = 1L,
                ),
            )
            val persisted =
                jdbcTemplate.queryForMap(
                    "SELECT status, version FROM approvals WHERE approval_id = ?",
                    approvalId.value,
                )
            assertThat(persisted["status"]).isEqualTo("APPROVED")
            assertThat((persisted["version"] as Number).toLong()).isEqualTo(1L)
            assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).hasSize(1)
            assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, 10)).isEmpty()
        }
    }

    @Test
    fun `concurrent approve and deny on one observed version commit a single decision`() {
        runBlocking {
            val requiredRole = ApproverRole("medical-reviewer")
            val requestResult =
                gateway.requestApproval(
                    subject = ApprovalSubject("test-concurrent-decision"),
                    recommendation =
                        ApprovalRecommendation(
                            type = "claim-review",
                            summary = "racing decisions",
                            payload = mapOf("amount" to "250"),
                        ),
                    requiredRole = requiredRole,
                    workflowRunId = WorkflowRunId("wf-test-concurrent-decision"),
                )
            val approvalId = (requestResult as ApprovalRequestResult.Suspended).approvalId
            val controlPlane: ApprovalDecisionControlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = approvalStore,
                    mutationStore = mutationStore,
                    authorizer = AllowAllApprovalDecisionAuthorizer,
                    clock = clock,
                )
            val approve =
                decisionCommand(approvalId, 0L, requiredRole, "approve", "corr-race-a")
            val deny =
                decisionCommand(approvalId, 0L, requiredRole, "deny", "corr-race-b")
            val pool =
                java.util.concurrent.Executors
                    .newFixedThreadPool(2)
            try {
                val approveResult =
                    pool.submit(java.util.concurrent.Callable { runBlocking { controlPlane.approve(approve) } })
                val denyResult = pool.submit(java.util.concurrent.Callable { runBlocking { controlPlane.deny(deny) } })
                val results =
                    listOf(
                        approveResult.get(60, java.util.concurrent.TimeUnit.SECONDS),
                        denyResult.get(60, java.util.concurrent.TimeUnit.SECONDS),
                    )

                val committed =
                    results.filter {
                        it is ApprovalDecisionResult.Approved || it is ApprovalDecisionResult.Denied
                    }
                assertThat(committed).describedAs("exactly one committed decision").hasSize(1)
                assertThat(results.filterIsInstance<ApprovalDecisionResult.Stale>())
                    .describedAs("the losing command is stale, not silently applied")
                    .hasSize(1)
                assertSingleCommittedDecision(approvalId)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    /** The racing pair must leave one increment and exactly one dispatchable audit intent. */
    private suspend fun assertSingleCommittedDecision(approvalId: dev.tramai.core.approval.gateway.ApprovalId) {
        val persisted =
            jdbcTemplate.queryForMap(
                "SELECT status, version FROM approvals WHERE approval_id = ?",
                approvalId.value,
            )
        assertThat((persisted["version"] as Number).toLong()).isEqualTo(1L)
        val pending = outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)
        assertThat(pending).describedAs("one dispatchable audit intent").hasSize(1)
        assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, 10)).isEmpty()
    }

    companion object {
        // Started eagerly (not via @Testcontainers/@Container) because the
        // @DynamicPropertySource lambdas below are resolved while the Spring
        // context loads — before JUnit's container lifecycle would start it.
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("sovereign_ops_decision_test")
                .withUsername("test")
                .withPassword("test")
                .apply { start() }

        private val keyFile =
            Files.createTempFile("tramai-sovereign-jdbc-key", ".b64").apply {
                toFile().writeText(
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(ByteArray(32) { it.toByte() }),
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
    }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("jdbc")
private annotation class JdbcTestTag

/**
 * Imported FIRST so its DataSource bean definition is registered before the
 * auto-configurations' condition evaluation (Spring processes @Import list
 * entries in order; the missing-datasource guard is `@ConditionalOnMissingBean`
 * and would otherwise match at parse time and fail the context).
 */
@SpringBootConfiguration
private open class JdbcSovereignOpsDataSourceConfig {
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
    open fun approvalGatewayRequestFactory(): ApprovalGatewayRequestFactory = JdbcTestApprovalGatewayRequestFactory()

    @Bean
    open fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)
}

@SpringBootConfiguration
@Import(
    JdbcSovereignOpsDataSourceConfig::class,
    SovereignJdbcPersistenceAutoConfiguration::class,
    ApprovalGatewayAutoConfiguration::class,
    ApprovalDecisionControlPlaneAutoConfiguration::class,
)
private open class JdbcSovereignOpsTestConfig

private class JdbcTestApprovalGatewayRequestFactory : ApprovalGatewayRequestFactory {
    override suspend fun createRequest(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalGatewayPersistenceRequest {
        val now = java.time.Instant.now()
        val approvalId = subject.value
        val wfRunId = workflowRunId?.value ?: "wf-$approvalId"
        val argumentsDigest = Sha256Digest.of("sha256:${sha256Hex("arguments:$approvalId")}")
        val workflowDigest = Sha256Digest.of("sha256:${sha256Hex("workflow:$approvalId")}")
        val tokenDigest = Sha256Digest.of("sha256:${sha256Hex("token:$approvalId")}")

        return ApprovalGatewayPersistenceRequest(
            approvalRequest =
                ApprovalRequest(
                    approvalId = approvalId,
                    binding =
                        ApprovalBinding(
                            workflowRunId = wfRunId,
                            toolName = "claim-triage",
                            argumentsDigest = argumentsDigest,
                            policyVersion = "v1",
                            workflowDigest = workflowDigest,
                            approvalTokenDigest = tokenDigest,
                        ),
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
                ),
            continuation =
                ApprovalContinuation(
                    approvalId = approvalId,
                    workflowRunId = wfRunId,
                    correlationId = "corr-$approvalId",
                    toolCallId = "tool-$approvalId",
                    toolName = "claim-triage",
                    argumentsDigest = argumentsDigest,
                    policyVersion = "v1",
                    workflowDigest = workflowDigest,
                    status = ApprovalContinuationStatus.PENDING,
                    createdAt = now,
                    approvalExpiresAt = now.plusSeconds(600),
                    claimedBy = null,
                    claimedAt = null,
                    completedAt = null,
                    version = 0L,
                ),
            sensitiveArguments =
                SensitiveToolArguments.of(
                    """{"subject":"${subject.value}","summary":"${recommendation.summary}"}""",
                ),
            suspendedInvocationMetadata =
                SuspendedInvocationMetadata(
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
                ),
            replayEnvelope = SensitiveReplayEnvelope.of(emptyList()),
            resumeToken = ResumeToken("resume-$approvalId"),
        )
    }
}

/** One-line command builder: the decision tests stay readable and inside the length ceiling. */
private fun decisionCommand(
    approvalId: dev.tramai.core.approval.gateway.ApprovalId,
    expectedVersion: Long,
    actorRole: ApproverRole,
    comment: String? = null,
    correlationId: String? = null,
): ApprovalDecisionCommand =
    ApprovalDecisionCommand(
        approvalId = approvalId,
        actorId = "medical-ops-reviewer",
        actorRole = actorRole,
        expectedVersion = expectedVersion,
        comment = comment,
        correlationId = correlationId,
    )

/** Shared by the nested fixture and the tests: hex SHA-256 of the given input. */
private fun sha256Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }
}
