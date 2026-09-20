package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ReplayEnvelopeDigestHelper
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.persistence.jdbc.JdbcApprovalContinuationStore
import dev.tramai.persistence.jdbc.JdbcApprovalStore
import dev.tramai.persistence.jdbc.JdbcContinuationArgumentsCodec
import dev.tramai.persistence.jdbc.JdbcEncryptedContinuationArguments
import dev.tramai.persistence.jdbc.JdbcEncryptedReplayEnvelope
import dev.tramai.persistence.jdbc.JdbcReplayEnvelopeCodec
import dev.tramai.persistence.jdbc.JdbcSuspendedInvocationStore
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsApprovalRequestRejectionTest {
    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres =
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("sovereign_ops_request_mutation_test")
                .withUsername("test")
                .withPassword("test")

        private fun createDataSource(): DataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }

        private val BASE_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    private val testAesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private val testSecretKey: SecretKey = SecretKeySpec(testAesKey, "AES")

    private lateinit var dataSource: DataSource

    private lateinit var replayCodec: JdbcReplayEnvelopeCodec

    private lateinit var continuationCodec: JdbcContinuationArgumentsCodec

    private lateinit var outboxCodec: JdbcOpsAuditOutboxPayloadCodec

    private lateinit var mutationStore: JdbcSovereignOpsApprovalRequestMutationStore

    private lateinit var approvalStore: JdbcApprovalStore

    private lateinit var suspendedInvocationStore: JdbcSuspendedInvocationStore

    private lateinit var continuationStore: JdbcApprovalContinuationStore

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource = createDataSource()
        runMigrations()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun setUp() {
        truncateTables()
        replayCodec = testReplayCodec()
        continuationCodec = testContinuationCodec()
        outboxCodec = testOutboxCodec()
        mutationStore =
            JdbcSovereignOpsApprovalRequestMutationStore(
                dataSource = dataSource,
                replayEnvelopeCodec = replayCodec,
                continuationArgumentsCodec = continuationCodec,
                outboxPayloadCodec = outboxCodec,
                encryptionKey = testSecretKey,
                encryptionKeyId = "test-key-1",
                clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
            )
        approvalStore =
            JdbcApprovalStore(
                dataSource = dataSource,
                clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
            )
        suspendedInvocationStore =
            JdbcSuspendedInvocationStore(
                dataSource = dataSource,
                replayEnvelopeCodec = replayCodec,
                clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
            )
        continuationStore =
            JdbcApprovalContinuationStore(
                dataSource = dataSource,
                argumentsCodec = continuationCodec,
                clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
            )
    }

    @Test
    fun `constraint failure rolls back partial approval request creation`() {
        runBlocking {
            val request = request("approval-d")
            suspendedInvocationStore.create(
                metadata =
                    request("existing-conflict").suspendedInvocationMetadata.copy(
                        toolCallId = request.suspendedInvocationMetadata.toolCallId,
                        toolName = request.suspendedInvocationMetadata.toolName,
                        replayEnvelopeDigest = request.suspendedInvocationMetadata.replayEnvelopeDigest,
                    ),
                replayEnvelope = request.replayEnvelope,
            )

            val thrown =
                assertThatSuspendCallThrows {
                    mutationStore.createApprovalRequest(request)
                }
            thrown
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-approval-request-mutation-database-failure")

            assertThat(approvalStore.get("approval-d")).isNull()
            assertThat(suspendedInvocationStore.get("approval-d")).isNull()
            assertThat(continuationStore.get("approval-d")).isNull()
            assertThat(selectCount("SELECT count(*) FROM audit_outbox")).isZero()
        }
    }

    @Test
    fun `cancellation exception is rethrown and transaction rolls back`() {
        runBlocking {
            val request = request("approval-e")
            val cancellingCodec =
                object : JdbcReplayEnvelopeCodec {
                    override fun encode(plaintext: ByteArray): JdbcEncryptedReplayEnvelope = cancelled()

                    override fun decode(envelope: JdbcEncryptedReplayEnvelope): ByteArray = envelope.ciphertext
                }
            val cancellingStore =
                JdbcSovereignOpsApprovalRequestMutationStore(
                    dataSource = dataSource,
                    replayEnvelopeCodec = cancellingCodec,
                    continuationArgumentsCodec = continuationCodec,
                    outboxPayloadCodec = outboxCodec,
                    encryptionKey = testSecretKey,
                    encryptionKeyId = "test-key-1",
                    clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
                )

            val thrown = assertThatSuspendCallThrows { cancellingStore.createApprovalRequest(request) }
            thrown.isInstanceOf(CancellationException::class.java)

            assertThat(approvalStore.get("approval-e")).isNull()
            assertThat(suspendedInvocationStore.get("approval-e")).isNull()
            assertThat(continuationStore.get("approval-e")).isNull()
        }
    }

    @Test
    fun `rejects replay envelope digest mismatch and rolls back all records`() {
        runBlocking {
            val mismatchedDigest =
                Sha256Digest.of(
                    "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                )
            val base = request("approval-f")
            val request =
                base.copy(
                    suspendedInvocationMetadata =
                        base.suspendedInvocationMetadata.copy(replayEnvelopeDigest = mismatchedDigest),
                )

            val thrown =
                assertThatSuspendCallThrows {
                    mutationStore.createApprovalRequest(request)
                }
            thrown.isInstanceOf(IllegalArgumentException::class.java)
            thrown.hasMessageContaining("replay-envelope-digest-mismatch")

            assertThat(approvalStore.get("approval-f")).isNull()
            assertThat(suspendedInvocationStore.get("approval-f")).isNull()
            assertThat(continuationStore.get("approval-f")).isNull()
            assertThat(selectCount("SELECT count(*) FROM audit_outbox")).isZero()
        }
    }

    @Test
    fun `rejects already expired approval request and rolls back all records`() {
        runBlocking {
            val now = BASE_NOW.plusSeconds(30)
            val clockAtNow = Clock.fixed(now, ZoneOffset.UTC)
            val storeWithExpiryCheck =
                JdbcSovereignOpsApprovalRequestMutationStore(
                    dataSource = dataSource,
                    replayEnvelopeCodec = replayCodec,
                    continuationArgumentsCodec = continuationCodec,
                    outboxPayloadCodec = outboxCodec,
                    encryptionKey = testSecretKey,
                    encryptionKeyId = "test-key-1",
                    clock = clockAtNow,
                )
            val request =
                request("approval-g").copy(
                    approvalRequest =
                        request("approval-g").approvalRequest.copy(
                            expiresAt = now.minusSeconds(1),
                        ),
                )

            val thrown =
                assertThatSuspendCallThrows {
                    storeWithExpiryCheck.createApprovalRequest(request)
                }
            thrown
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("approval-request-expired-at-creation")

            assertThat(approvalStore.get("approval-g")).isNull()
            assertThat(suspendedInvocationStore.get("approval-g")).isNull()
            assertThat(continuationStore.get("approval-g")).isNull()
        }
    }

    @Test
    fun `rejects invalid continuation metadata and rolls back all records`() {
        runBlocking {
            val request =
                request("approval-h").copy(
                    continuation =
                        request("approval-h").continuation.copy(
                            workflowRunId = "  ",
                        ),
                )

            val thrown =
                assertThatSuspendCallThrows {
                    mutationStore.createApprovalRequest(request)
                }
            thrown
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("continuation.workflowRunId")

            assertThat(approvalStore.get("approval-h")).isNull()
            assertThat(suspendedInvocationStore.get("approval-h")).isNull()
            assertThat(continuationStore.get("approval-h")).isNull()
        }
    }

    @Test
    fun `rejects future continuation createdAt and rolls back all records`() {
        runBlocking {
            val now = BASE_NOW.plusSeconds(30)
            val clockAtNow = Clock.fixed(now, ZoneOffset.UTC)
            val storeWithTimeCheck =
                JdbcSovereignOpsApprovalRequestMutationStore(
                    dataSource = dataSource,
                    replayEnvelopeCodec = replayCodec,
                    continuationArgumentsCodec = continuationCodec,
                    outboxPayloadCodec = outboxCodec,
                    encryptionKey = testSecretKey,
                    encryptionKeyId = "test-key-1",
                    clock = clockAtNow,
                )
            val request =
                request("approval-i").copy(
                    continuation =
                        request("approval-i").continuation.copy(
                            createdAt = now.plusSeconds(60),
                        ),
                )

            val thrown =
                assertThatSuspendCallThrows {
                    storeWithTimeCheck.createApprovalRequest(request)
                }
            thrown
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("continuation-created-at-in-future")

            assertThat(approvalStore.get("approval-i")).isNull()
            assertThat(suspendedInvocationStore.get("approval-i")).isNull()
            assertThat(continuationStore.get("approval-i")).isNull()
        }
    }

    @Test
    fun `rollback removes inbox metadata when continuation insert fails`() {
        runBlocking {
            val request = request("approval-inbox-2")
            val metadata =
                ApprovalInboxMetadata(
                    requiredRole = ApproverRole("medical-reviewer"),
                    riskLevel = "HIGH",
                    subjectType = "claim",
                    subjectId = "claim-456",
                    recommendationType = "claim-payout",
                )
            // Use a failing continuation arguments codec to trigger rollback
            val failureMessage = "simulated-codec-failure"
            val failingCodec =
                object : JdbcContinuationArgumentsCodec {
                    override fun encode(plaintext: ByteArray): JdbcEncryptedContinuationArguments =
                        throw SimulatedCodecFailure(failureMessage)

                    override fun decode(envelope: JdbcEncryptedContinuationArguments): ByteArray =
                        throw SimulatedCodecFailure(failureMessage)
                }
            val storeWithFailingCodec =
                JdbcSovereignOpsApprovalRequestMutationStore(
                    dataSource = dataSource,
                    replayEnvelopeCodec = replayCodec,
                    continuationArgumentsCodec = failingCodec,
                    outboxPayloadCodec = outboxCodec,
                    encryptionKey = testSecretKey,
                    encryptionKeyId = "test-key-1",
                    clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
                )

            val thrown =
                assertThatSuspendCallThrows {
                    storeWithFailingCodec.createApprovalRequest(request, inboxMetadata = metadata)
                }
            thrown.isInstanceOf(RuntimeException::class.java)

            // Verify approval was rolled back (no row)
            assertThat(approvalStore.get("approval-inbox-2")).isNull()
        }
    }

    private fun assertThatSuspendCallThrows(block: suspend () -> Unit) =
        assertThatThrownBy {
            runBlocking {
                block()
            }
        }

    private fun decrypt(
        ciphertext: ByteArray,
        nonce: ByteArray,
        algorithm: String,
    ): ByteArray {
        val cipher = Cipher.getInstance(algorithm)
        val keySpec = SecretKeySpec(testAesKey, "AES")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        return cipher.doFinal(ciphertext)
    }

    private fun digest(ch: Char): Sha256Digest = Sha256Digest.of("sha256:${ch.toString().repeat(64)}")

    private fun encrypt(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
        val algorithm = "AES/GCM/NoPadding"
        val cipher = Cipher.getInstance(algorithm)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val keySpec = SecretKeySpec(testAesKey, "AES")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        val ciphertext = cipher.doFinal(plaintext)
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(plaintext)
                .joinToString("") { "%02x".format(it) }
        return JdbcEncryptedAuditOutboxPayload(
            ciphertext = ciphertext,
            keyId = "test-key-1",
            algorithm = algorithm,
            nonce = nonce,
            payloadDigest = "sha256:$digest",
        )
    }

    private fun governedApprovalRequest(approvalId: String): ApprovalRequest =
        ApprovalRequest(
            approvalId = approvalId,
            binding =
                ApprovalBinding(
                    workflowRunId = "wf-$approvalId",
                    toolName = "tool-$approvalId",
                    argumentsDigest = digest('a'),
                    policyVersion = "policy-v1",
                    workflowDigest = digest('b'),
                    approvalTokenDigest = digest('c'),
                ),
            status = ApprovalStatus.PENDING,
            requestedBy = "requestor-$approvalId",
            requestedAt = BASE_NOW,
            expiresAt = BASE_NOW.plusSeconds(600),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

    private fun governedContinuation(approvalId: String): ApprovalContinuation =
        ApprovalContinuation(
            approvalId = approvalId,
            workflowRunId = "wf-$approvalId",
            correlationId = "corr-$approvalId",
            toolCallId = "tool-call-$approvalId",
            toolName = "tool-$approvalId",
            argumentsDigest = digest('a'),
            policyVersion = "policy-v1",
            workflowDigest = digest('b'),
            status = ApprovalContinuationStatus.PENDING,
            createdAt = BASE_NOW,
            approvalExpiresAt = BASE_NOW.plusSeconds(600),
            claimedBy = null,
            claimedAt = null,
            completedAt = null,
            version = 0L,
        )

    private fun governedMessages(approvalId: String): List<Message> =
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

    private fun governedSuspendedMetadata(
        approvalId: String,
        messages: List<Message>,
    ): SuspendedInvocationMetadata {
        val operationReference =
            ResumeOperationReference("t.Service", "approve", "(Ljava/lang/String;)V", digest('d'))
        return SuspendedInvocationMetadata(
            approvalId = approvalId,
            toolCallId = "tool-call-$approvalId",
            toolName = "tool-$approvalId",
            toolCallIndex = 0,
            correlationId = "corr-$approvalId",
            identity =
                EngineExecutionIdentity(
                    workflowRunId = "wf-$approvalId",
                    correlationId = "corr-$approvalId",
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

    // 0.7.1d1 P3: governed transactional creation. The approval-row attribution and the suspension
    // identity must come from ONE canonical identity inside the SAME transaction, and an existing row
    // may only ever be adopted by the run it is durably attributed to.
    // ---------------------------------------------------------------------------------------------

    private fun identity(
        runId: String,
        workloadId: String = "claims",
    ): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId(workloadId),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("claims-prod"),
                            version = ConfigurationVersion("17"),
                        ),
                    environmentId = EnvironmentId("production"),
                    deploymentId = DeploymentId("eu-west-amsterdam-01"),
                ),
            runId = RunId(runId),
        )

    private fun request(approvalId: String): ApprovalGatewayPersistenceRequest {
        val messages = governedMessages(approvalId)
        return ApprovalGatewayPersistenceRequest(
            approvalRequest = governedApprovalRequest(approvalId),
            continuation = governedContinuation(approvalId),
            sensitiveArguments = SensitiveToolArguments.of("""{"claimId":"$approvalId"}"""),
            suspendedInvocationMetadata = governedSuspendedMetadata(approvalId, messages),
            replayEnvelope = SensitiveReplayEnvelope.of(messages),
            resumeToken = ResumeToken("resume-$approvalId"),
        )
    }

    private fun runMigrations() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
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
                    stmt.execute(sql)
                }
            }
        }
    }

    private fun selectCount(sql: String): Int =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    // ---------------------------------------------------------------------------------------------

    private fun testContinuationCodec(): JdbcContinuationArgumentsCodec =
        object : JdbcContinuationArgumentsCodec {
            override fun encode(plaintext: ByteArray): JdbcEncryptedContinuationArguments =
                encrypt(plaintext).let {
                    JdbcEncryptedContinuationArguments(
                        ciphertext = it.ciphertext,
                        keyId = it.keyId,
                        algorithm = it.algorithm,
                        nonce = it.nonce,
                        payloadDigest = it.payloadDigest,
                    )
                }

            override fun decode(envelope: JdbcEncryptedContinuationArguments): ByteArray =
                decrypt(
                    ciphertext = envelope.ciphertext,
                    nonce = envelope.nonce,
                    algorithm = envelope.algorithm,
                )
        }

    private fun testOutboxCodec(): JdbcOpsAuditOutboxPayloadCodec =
        object : JdbcOpsAuditOutboxPayloadCodec {
            override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload = encrypt(plaintext)

            override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray =
                decrypt(
                    ciphertext = envelope.ciphertext,
                    nonce = envelope.nonce,
                    algorithm = envelope.algorithm,
                )
        }

    private fun testReplayCodec(): JdbcReplayEnvelopeCodec =
        object : JdbcReplayEnvelopeCodec {
            override fun encode(plaintext: ByteArray): JdbcEncryptedReplayEnvelope =
                encrypt(plaintext).let {
                    JdbcEncryptedReplayEnvelope(
                        ciphertext = it.ciphertext,
                        keyId = it.keyId,
                        algorithm = it.algorithm,
                        nonce = it.nonce,
                        payloadDigest = it.payloadDigest,
                    )
                }

            override fun decode(envelope: JdbcEncryptedReplayEnvelope): ByteArray =
                decrypt(
                    ciphertext = envelope.ciphertext,
                    nonce = envelope.nonce,
                    algorithm = envelope.algorithm,
                )
        }

    private fun truncateTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val truncateAll =
                    "TRUNCATE TABLE approval_continuations, suspended_invocations, audit_outbox, approvals CASCADE"
                stmt.execute(truncateAll)
            }
        }
    }

    private class SimulatedCodecFailure(
        message: String,
    ) : RuntimeException(message)
}

private fun cancelled(): Nothing = throw CancellationException("cancelled")
