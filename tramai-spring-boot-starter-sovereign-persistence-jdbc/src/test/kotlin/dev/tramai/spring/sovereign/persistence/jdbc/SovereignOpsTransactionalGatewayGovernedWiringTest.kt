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
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.GovernedSuspendedInvocationStore
import dev.tramai.engine.ReplayEnvelopeDigestHelper
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.engine.approval.encodeApprovalAttribution
import dev.tramai.spring.sovereign.ops.ApprovalGatewayAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsApprovalRequestMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import javax.sql.DataSource

/**
 * Proves the governed capability is reachable through the DEFAULT production entry point.
 *
 * The lower-level suites prove the governed mutation machinery works when it is constructed and
 * handed to the gateway directly. That is not the same claim: a perfectly correct governed store
 * that auto-configuration never exposes, and a gateway that rejects governed runs before consulting
 * it, both leave production without the carriage 0.7.1d1 requires. This test therefore starts at the
 * auto-configured gateway, inside a real [GovernedRunScope], against the store the auto-configuration
 * itself produces, and asserts the identity that actually reached durable state.
 */
@SpringBootTest(classes = [GovernedGatewayWiringTestConfig::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SovereignOpsTransactionalGatewayGovernedWiringTest {
    @Autowired
    private lateinit var gatewayProvider: ObjectProvider<ApprovalGateway>

    @Autowired
    private lateinit var mutationStoreProvider: ObjectProvider<SovereignOpsApprovalRequestMutationStore>

    @Autowired
    private lateinit var suspensionStore: SuspendedInvocationStore

    private val gateway: ApprovalGateway get() = requireNotNull(gatewayProvider.ifAvailable)
    private val mutationStore: SovereignOpsApprovalRequestMutationStore
        get() = requireNotNull(mutationStoreProvider.ifAvailable)

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val identity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("claims-intake"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("claim-triage"),
                            version = ConfigurationVersion("7"),
                        ),
                    environmentId = EnvironmentId("prod-eu"),
                    deploymentId = DeploymentId("deploy-42"),
                ),
            runId = RunId("governed-run-1"),
        )

    @BeforeAll
    fun runMigrations() {
        listOf(
            "V1__sovereign_persistence.sql",
            "V2__approval_continuations.sql",
            "V4__audit_outbox_hardening.sql",
            "V6__approval_resume_credential_custody.sql",
            "V7__approval_continuations_resume_retry.sql",
        ).forEach { file ->
            val sql =
                javaClass.classLoader
                    .getResourceAsStream("tramai/persistence/jdbc/postgres/$file")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("Migration not found: $file")
            runCatching { jdbcTemplate.execute(sql) }
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

    @Test
    fun `auto-configuration exposes the governed mutation capability`() {
        assertThat(mutationStore).isInstanceOf(GovernedSovereignOpsApprovalRequestMutationStore::class.java)
    }

    @Test
    fun `governed run suspends through the auto-configured gateway and persists canonical identity`() {
        val approvalId = "governed-wiring-1"

        val result =
            runBlocking {
                withContext(GovernedRunScope(identity)) {
                    gateway.requestApproval(
                        subject = ApprovalSubject(approvalId),
                        recommendation =
                            ApprovalRecommendation(
                                type = "claim-review",
                                summary = "governed wiring",
                                payload = emptyMap(),
                            ),
                        requiredRole = ApproverRole("medical-reviewer"),
                        workflowRunId = WorkflowRunId(identity.runId.value),
                    )
                }
            }

        assertThat(result).isInstanceOf(ApprovalRequestResult.Suspended::class.java)

        // (1) The approval-row attribution snapshot decoded from durable state is the canonical
        // identity, five components and no run-id copy.
        val metadata =
            com.fasterxml.jackson.databind
                .ObjectMapper()
                .readTree(
                    jdbcTemplate.queryForObject(
                        "SELECT sanitized_metadata FROM approvals WHERE approval_id = ?",
                        String::class.java,
                        approvalId,
                    ),
                )
        val attribution = metadata["attribution"]
        assertThat(attribution).isNotNull
        encodeApprovalAttribution(identity).forEach { (key, value) ->
            assertThat(attribution!![key]?.asText())
                .describedAs("attribution component %s", key)
                .isEqualTo(value)
        }
        assertThat(attribution!!.has(identity.runId.value)).isFalse

        // (2) The suspension is the canonical full identity record for the same run.
        val suspensions = suspensionStore
        assertThat(suspensions).isInstanceOf(GovernedSuspendedInvocationStore::class.java)
        val persistedIdentity =
            runBlocking { (suspensions as GovernedSuspendedInvocationStore).governedRunIdentity(approvalId) }
        assertThat(persistedIdentity).isEqualTo(identity)
    }

    companion object {
        private val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("sovereign_ops_gateway_wiring_test")

        /** A real 256-bit AES key on disk: the production key-file source, not a test-only shortcut. */
        private val keyFile: java.nio.file.Path =
            java.nio.file.Files
                .createTempFile("tramai-wiring-key", ".b64")
                .also {
                    java.nio.file.Files.write(
                        it,
                        java.util.Base64
                            .getEncoder()
                            .encodeToString(ByteArray(32) { 7 })
                            .toByteArray(),
                    )
                }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            if (!postgres.isRunning) postgres.start()
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            // The JDBC persistence auto-configuration is gated on this; without it the whole
            // governed store wiring is skipped and the test would prove nothing.
            registry.add("tramai.sovereign.persistence.type") { "jdbc" }
            registry.add("tramai.sovereign.persistence.encryption.key-file") { keyFile.toString() }
        }
    }
}

/**
 * Imported first so its DataSource bean definition exists before the auto-configurations' condition
 * evaluation — the missing-datasource guard is `@ConditionalOnMissingBean` and would otherwise match
 * at parse time and fail the context.
 */
@SpringBootConfiguration
private open class GovernedGatewayDataSourceConfig {
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
    open fun approvalGatewayRequestFactory(): ApprovalGatewayRequestFactory = GovernedWiringRequestFactory()

    @Bean
    open fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)
}

@SpringBootConfiguration
@Import(
    GovernedGatewayDataSourceConfig::class,
    SovereignJdbcPersistenceAutoConfiguration::class,
    ApprovalGatewayAutoConfiguration::class,
)
private open class GovernedGatewayWiringTestConfig

/**
 * Identity-blind, like every production factory: it echoes the run id it is handed and never
 * nominates attribution. A governed run id therefore has exactly one source — the gateway.
 */
private class GovernedWiringRequestFactory : ApprovalGatewayRequestFactory {
    private fun digest(ch: Char): Sha256Digest = Sha256Digest.of("sha256:${ch.toString().repeat(64)}")

    private fun messages(approvalId: String): List<Message> =
        listOf(
            Message(role = MessageRole.USER, content = "request-$approvalId"),
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls =
                    listOf(
                        ToolCall(
                            id = "tool-call-$approvalId",
                            name = "tool-$approvalId",
                            argumentsJson = "__redacted_approval_continuation_args__",
                        ),
                    ),
            ),
        )

    override suspend fun createRequest(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalGatewayPersistenceRequest {
        val now: Instant = Instant.now()
        val approvalId = subject.value
        // The factory echoes the run id it is handed and nominates nothing: a governed run id has
        // exactly one source, the gateway's resolved canonical identity.
        val runId = workflowRunId?.value ?: "wf-$approvalId"
        val correlationId = "corr-$approvalId"
        val messages = messages(approvalId)
        val operationReference =
            ResumeOperationReference("t.Service", "approve", "(Ljava/lang/String;)V", digest('d'))

        return ApprovalGatewayPersistenceRequest(
            approvalRequest = approvalRequest(approvalId, runId, now),
            continuation = continuation(approvalId, runId, now),
            sensitiveArguments = SensitiveToolArguments.of("""{"claimId":"$approvalId"}"""),
            suspendedInvocationMetadata =
                suspendedMetadata(approvalId, runId, correlationId, messages, operationReference),
            replayEnvelope = SensitiveReplayEnvelope.of(messages),
            resumeToken = ResumeToken("resume-$approvalId"),
        )
    }

    private fun approvalRequest(
        approvalId: String,
        runId: String,
        now: Instant,
    ): ApprovalRequest =
        ApprovalRequest(
            approvalId = approvalId,
            binding =
                ApprovalBinding(
                    workflowRunId = runId,
                    toolName = "tool-$approvalId",
                    argumentsDigest = digest('a'),
                    policyVersion = "policy-v1",
                    workflowDigest = digest('b'),
                    approvalTokenDigest = digest('c'),
                ),
            status = ApprovalStatus.PENDING,
            requestedBy = "requestor-$approvalId",
            requestedAt = now,
            expiresAt = now.plusSeconds(600),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

    private fun continuation(
        approvalId: String,
        runId: String,
        now: Instant,
    ): ApprovalContinuation =
        ApprovalContinuation(
            approvalId = approvalId,
            workflowRunId = runId,
            correlationId = "corr-$approvalId",
            toolCallId = "tool-call-$approvalId",
            toolName = "tool-$approvalId",
            argumentsDigest = digest('a'),
            policyVersion = "policy-v1",
            workflowDigest = digest('b'),
            status = ApprovalContinuationStatus.PENDING,
            createdAt = now,
            approvalExpiresAt = now.plusSeconds(600),
            claimedBy = null,
            claimedAt = null,
            completedAt = null,
            version = 0L,
        )

    private fun suspendedMetadata(
        approvalId: String,
        runId: String,
        correlationId: String,
        messages: List<Message>,
        operationReference: ResumeOperationReference,
    ): SuspendedInvocationMetadata =
        SuspendedInvocationMetadata(
            approvalId = approvalId,
            toolCallId = "tool-call-$approvalId",
            toolName = "tool-$approvalId",
            toolCallIndex = 0,
            correlationId = correlationId,
            identity =
                EngineExecutionIdentity(
                    workflowRunId = runId,
                    correlationId = correlationId,
                    workflowDigest = digest('b'),
                    policyVersion = "policy-v1",
                    actorId = "requestor-$approvalId",
                ),
            securityContext = ExecutionSecurityContext(),
            operationReference = operationReference,
            replayEnvelopeDigest = ReplayEnvelopeDigestHelper.compute(operationReference, messages),
            toolReference = ResumeToolReference("tool-$approvalId", digest('e')),
        )
}
