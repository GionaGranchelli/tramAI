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
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

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
@Testcontainers
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
        ).forEach { resource ->
            val sql = javaClass.classLoader
                .getResourceAsStream(resource)
                ?.bufferedReader()
                ?.readText()
                ?: error("Migration not found: $resource")
            runCatching { jdbcTemplate.execute(sql) }
        }
    }

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE approval_continuations, suspended_invocations, audit_outbox, approvals CASCADE")
    }

    @Test
    fun `approve approval creates pending approval-approved outbox record atomically`() = runBlocking {
        val requiredRole = ApproverRole("medical-reviewer")
        val requestResult = gateway.requestApproval(
            subject = ApprovalSubject("test-approve-1"),
            recommendation = ApprovalRecommendation(
                type = "claim-review",
                summary = "high-value claim",
                payload = mapOf("amount" to "5000"),
            ),
            requiredRole = requiredRole,
            workflowRunId = WorkflowRunId("wf-test-approve-1"),
        )
        val approvalId = (requestResult as ApprovalRequestResult.Suspended).approvalId

        val controlPlane: ApprovalDecisionControlPlane = SovereignOpsApprovalDecisionControlPlane(
            approvalStore = approvalStore,
            mutationStore = mutationStore,
            authorizer = AllowAllApprovalDecisionAuthorizer,
            clock = clock,
        )

        val result = controlPlane.approve(
            ApprovalDecisionCommand(
                approvalId = approvalId,
                actorId = "medical-ops-reviewer",
                actorRole = requiredRole,
                comment = "Medical necessity established",
                correlationId = "corr-approve-test-1",
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
        assertThat(approvalOutbox!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
        assertThat(approvalOutbox.approvalStatus).isEqualTo(ApprovalStatus.APPROVED.name)
        assertThat(approvalOutbox.actor).isEqualTo("medical-ops-reviewer")
    }

    @Test
    fun `can deny two different approvals without audit outbox event key conflict`() = runBlocking {
        val requiredRole = ApproverRole("medical-reviewer")

        val result1 = gateway.requestApproval(
            subject = ApprovalSubject("claim-deny-conflict-1"),
            recommendation = ApprovalRecommendation(
                type = "claim-review",
                summary = "review required",
            ),
            requiredRole = requiredRole,
            workflowRunId = WorkflowRunId("wf-deny-conflict-1"),
        )
        val result2 = gateway.requestApproval(
            subject = ApprovalSubject("claim-deny-conflict-2"),
            recommendation = ApprovalRecommendation(
                type = "claim-review",
                summary = "review required",
            ),
            requiredRole = requiredRole,
            workflowRunId = WorkflowRunId("wf-deny-conflict-2"),
        )
        val id1 = (result1 as ApprovalRequestResult.Suspended).approvalId
        val id2 = (result2 as ApprovalRequestResult.Suspended).approvalId

        val controlPlane: ApprovalDecisionControlPlane = SovereignOpsApprovalDecisionControlPlane(
            approvalStore = approvalStore,
            mutationStore = mutationStore,
            authorizer = AllowAllApprovalDecisionAuthorizer,
            clock = clock,
        )

        val deny1 = controlPlane.deny(
            ApprovalDecisionCommand(
                approvalId = id1,
                actorId = "reviewer",
                actorRole = requiredRole,
                correlationId = "c1",
            ),
        )
        val deny2 = controlPlane.deny(
            ApprovalDecisionCommand(
                approvalId = id2,
                actorId = "reviewer",
                actorRole = requiredRole,
                correlationId = "c2",
            ),
        )

        assertThat(deny1).isInstanceOf(ApprovalDecisionResult.Denied::class.java)
        assertThat(deny2).isInstanceOf(ApprovalDecisionResult.Denied::class.java)

        val outbox1 = outboxStore.findByEventKey("approval-denied.${id1.value}")
        val outbox2 = outboxStore.findByEventKey("approval-denied.${id2.value}")
        assertThat(outbox1).isNotNull
        assertThat(outbox2).isNotNull
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("sovereign_ops_decision_test")
            .withUsername("test")
            .withPassword("test")

        private val keyFile = Files.createTempFile("tramai-sovereign-jdbc-key", ".b64").apply {
            toFile().writeText(
                java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
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

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(
    SovereignJdbcPersistenceAutoConfiguration::class,
    ApprovalGatewayAutoConfiguration::class,
    ApprovalDecisionControlPlaneAutoConfiguration::class,
)
private open class JdbcSovereignOpsTestConfig {

    @Bean
    open fun approvalGatewayRequestFactory(): ApprovalGatewayRequestFactory = JdbcTestApprovalGatewayRequestFactory()
}

private class JdbcTestApprovalGatewayRequestFactory : ApprovalGatewayRequestFactory {
    override suspend fun createRequest(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalGatewayPersistenceRequest {
        val now = Instant.parse("2026-06-25T10:00:00Z")
        val approvalId = subject.value
        val wfRunId = workflowRunId?.value ?: "wf-$approvalId"
        val argumentsDigest = Sha256Digest.of("sha256:${"0".repeat(64)}")
        val workflowDigest = Sha256Digest.of("sha256:${"1".repeat(64)}")
        val tokenDigest = Sha256Digest.of("sha256:${"2".repeat(64)}")

        return ApprovalGatewayPersistenceRequest(
            approvalRequest = ApprovalRequest(
                approvalId = approvalId,
                binding = ApprovalBinding(
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
                expiresAt = now.plusSeconds(3600),
                decidedBy = null,
                decidedAt = null,
                decisionComment = null,
                consumedBy = null,
                consumedAt = null,
                version = 0L,
            ),
            continuation = ApprovalContinuation(
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
                approvalExpiresAt = now.plusSeconds(3600),
                claimedBy = null,
                claimedAt = null,
                completedAt = null,
                version = 0L,
            ),
            sensitiveArguments = SensitiveToolArguments.of("""{"subject":"${subject.value}","summary":"${recommendation.summary}"}"""),
            suspendedInvocationMetadata = SuspendedInvocationMetadata(
                approvalId = approvalId,
                toolCallId = "tool-$approvalId",
                toolName = "claim-triage",
                toolCallIndex = 0,
                correlationId = "corr-$approvalId",
                identity = EngineExecutionIdentity(
                    workflowRunId = wfRunId,
                    correlationId = "corr-$approvalId",
                    workflowDigest = workflowDigest,
                    policyVersion = "v1",
                    actorId = "test-requester",
                ),
                securityContext = ExecutionSecurityContext(),
                operationReference = ResumeOperationReference(
                    serviceInterface = "dev.tramai.test.Workflow",
                    methodName = "triage",
                    jvmMethodDescriptor = "(Ljava/lang/String;)V",
                    resumeDefinitionDigest = argumentsDigest,
                ),
                replayEnvelopeDigest = argumentsDigest,
                toolReference = ResumeToolReference("claim-triage", argumentsDigest),
            ),
            replayEnvelope = SensitiveReplayEnvelope.of(emptyList()),
            resumeToken = ResumeToken("resume-$approvalId"),
        )
    }
}
