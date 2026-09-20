package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.SealedResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.core.exception.GovernedRunContinuityException
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
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.ReplayEnvelopeDigestHelper
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.approval.ApprovalAttributionCorruptionException
import dev.tramai.engine.approval.ApprovalAttributionKeys
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.persistence.jdbc.GovernedJdbcSuspendedInvocationStore
import dev.tramai.persistence.jdbc.JdbcApprovalContinuationStore
import dev.tramai.persistence.jdbc.JdbcApprovalStore
import dev.tramai.persistence.jdbc.JdbcContinuationArgumentsCodec
import dev.tramai.persistence.jdbc.JdbcEncryptedContinuationArguments
import dev.tramai.persistence.jdbc.JdbcEncryptedReplayEnvelope
import dev.tramai.persistence.jdbc.JdbcReplayEnvelopeCodec
import dev.tramai.persistence.jdbc.JdbcSuspendedInvocationStore
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadata
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsApprovalRequestMutationStoreTest {
    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres =
            PostgreSQLContainer(POSTGRES_IMAGE)
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
    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

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
    fun `create approval request persists approval suspended invocation and continuation atomically`() {
        runBlocking {
            val request = request("approval-a")

            val result = mutationStore.createApprovalRequest(request)

            assertThat(result).isEqualTo(
                SovereignOpsApprovalRequestMutationResult.Created(
                    approvalId = "approval-a",
                    correlationId = "corr-approval-a",
                    resumeToken = ResumeToken("resume-approval-a"),
                ),
            )

            val approval = approvalStore.get("approval-a")
            assertThat(approval).isNotNull
            assertThat(approval!!.status).isEqualTo(ApprovalStatus.PENDING)

            val suspended = suspendedInvocationStore.get("approval-a")
            assertThat(suspended).isNotNull
            assertThat(suspended!!.correlationId).isEqualTo("corr-approval-a")

            val replayEnvelope = suspendedInvocationStore.revealReplayEnvelope("approval-a")
            assertThat(replayEnvelope).isNotNull
            assertThat(replayEnvelope!!.revealForResume().messages)
                .extracting<String> { it.content }
                .containsExactly("request-approval-a", "")

            val continuation = continuationStore.get("approval-a")
            assertThat(continuation).isNotNull
            assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)
            assertThat(selectCount("SELECT count(*) FROM audit_outbox")).isZero()
        }
    }

    @Test
    fun `create approval request with audit intent also creates pending outbox record`() {
        runBlocking {
            val request = request("approval-b")
            val auditIntent = auditIntent("approval-b", "event-key-b")

            mutationStore.createApprovalRequest(request, auditIntent)

            assertThat(selectValue("SELECT status FROM audit_outbox WHERE outbox_id = ?", auditIntent.outboxId))
                .isEqualTo("PENDING")
            assertThat(selectCount("SELECT count(*) FROM audit_outbox WHERE event_key = 'event-key-b'")).isEqualTo(1)
        }
    }

    @Test
    fun `duplicate approval request returns existing`() {
        runBlocking {
            val request = request("approval-c")

            val created = mutationStore.createApprovalRequest(request)
            val duplicate = mutationStore.createApprovalRequest(request)

            assertThat(created).isInstanceOf(SovereignOpsApprovalRequestMutationResult.Created::class.java)
            assertThat(duplicate).isInstanceOf(SovereignOpsApprovalRequestMutationResult.Existing::class.java)
            val existing = duplicate as SovereignOpsApprovalRequestMutationResult.Existing
            assertThat(existing.approval.approvalId).isEqualTo("approval-c")
            assertThat(selectCount("SELECT count(*) FROM approvals WHERE approval_id = 'approval-c'")).isEqualTo(1)
        }
    }

    @Test
    fun `audit outbox failure rolls back approval request creation`() {
        runBlocking {
            val request = request("approval-j")
            val auditIntent = auditIntent("approval-j", "outbox-failure-test")
            val failingCodec =
                object : JdbcOpsAuditOutboxPayloadCodec {
                    override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload =
                        throw IllegalStateException("simulated-outbox-codec-failure")

                    override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray = unsupported()
                }
            val storeWithFailingOutbox =
                JdbcSovereignOpsApprovalRequestMutationStore(
                    dataSource = dataSource,
                    replayEnvelopeCodec = replayCodec,
                    continuationArgumentsCodec = continuationCodec,
                    outboxPayloadCodec = failingCodec,
                    encryptionKey = testSecretKey,
                    encryptionKeyId = "test-key-1",
                    clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
                )

            val thrown =
                assertThatSuspendCallThrows {
                    storeWithFailingOutbox.createApprovalRequest(request, auditIntent)
                }
            thrown
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("simulated-outbox-codec-failure")

            assertThat(approvalStore.get("approval-j")).isNull()
            assertThat(suspendedInvocationStore.get("approval-j")).isNull()
            assertThat(continuationStore.get("approval-j")).isNull()
            val outboxCount =
                selectCount("SELECT count(*) FROM audit_outbox WHERE event_key = 'outbox-failure-test'")
            assertThat(outboxCount).isZero()
        }
    }

    // ── Inbox metadata tests ──

    @Test
    fun `approval request creation persists inbox metadata atomically`() {
        runBlocking {
            val request = request("approval-inbox-1")
            val metadata =
                ApprovalInboxMetadata(
                    requiredRole = ApproverRole("medical-reviewer"),
                    riskLevel = "HIGH",
                    subjectType = "claim",
                    subjectId = "claim-123",
                    recommendationType = "claim-payout",
                )

            val result = mutationStore.createApprovalRequest(request, inboxMetadata = metadata)

            assertThat(result).isInstanceOf(SovereignOpsApprovalRequestMutationResult.Created::class.java)

            // Read sanitized_metadata JSON from DB and verify inbox fields via parsed tree
            val rawJson =
                selectValue(
                    "SELECT sanitized_metadata::text FROM approvals WHERE approval_id = ?",
                    "approval-inbox-1",
                )!!
            val root = mapper.readTree(rawJson)
            val inbox = root["inbox"]
            assertThat(inbox).isNotNull
            assertThat(inbox["requiredRole"].asText()).isEqualTo("medical-reviewer")
            assertThat(inbox["riskLevel"].asText()).isEqualTo("HIGH")
            assertThat(inbox["subjectType"].asText()).isEqualTo("claim")
            assertThat(inbox["subjectId"].asText()).isEqualTo("claim-123")
            assertThat(inbox["recommendationType"].asText()).isEqualTo("claim-payout")

            // Verify inbox does not contain sensitive binding fields (they live under binding)
            assertThat(inbox.has("argumentsDigest")).isFalse()
            assertThat(inbox.has("approvalTokenDigest")).isFalse()
        }
    }

    @Test
    fun `existing approval replay returns existing metadata safely without inbox changes`() {
        runBlocking {
            val request = request("approval-inbox-3")
            val metadata =
                ApprovalInboxMetadata(
                    requiredRole = ApproverRole("medical-reviewer"),
                    riskLevel = "HIGH",
                    subjectType = "claim",
                    subjectId = "claim-789",
                    recommendationType = "claim-payout",
                )

            // First create with metadata
            mutationStore.createApprovalRequest(request, inboxMetadata = metadata)

            // Second call — should return Existing, not Created
            val result = mutationStore.createApprovalRequest(request, inboxMetadata = metadata)

            assertThat(result).isInstanceOf(SovereignOpsApprovalRequestMutationResult.Existing::class.java)
            val existing = result as SovereignOpsApprovalRequestMutationResult.Existing
            assertThat(existing.approval.approvalId).isEqualTo("approval-inbox-3")

            // Only one row exists
            val count = selectCount("SELECT count(*) FROM approvals WHERE approval_id = 'approval-inbox-3'")
            assertThat(count).isEqualTo(1)
        }
    }

    @Test
    fun `transactional resume credential uses configured encryption key id`() {
        runBlocking {
            val request = request("cred-key-id-test")
            val metadata =
                ApprovalInboxMetadata(
                    requiredRole = ApproverRole("medical-reviewer"),
                    riskLevel = "HIGH",
                    subjectType = "claim",
                    subjectId = "claim-key-id",
                    recommendationType = "claim-payout",
                )
            val resumeCredential =
                ApprovalResumeCredentialRecord(
                    approvalId = ApprovalId("cred-key-id-test"),
                    workflowRunId = WorkflowRunId("wf-cred-key-id-test"),
                    resumeToken =
                        SealedResumeToken.seal(
                            ResumeToken("test-resume-token"),
                        ),
                    createdAt = BASE_NOW,
                    expiresAt = BASE_NOW.plusSeconds(600),
                    version = 1L,
                )

            mutationStore.createApprovalRequest(
                request = request,
                inboxMetadata = metadata,
                resumeCredential = resumeCredential,
            )

            // Read encryption_key_id from the resume credentials table
            val keyId =
                selectValue(
                    "SELECT encryption_key_id FROM tramai_approval_resume_credentials WHERE approval_id = ?",
                    "cred-key-id-test",
                )

            assertThat(keyId).isEqualTo("test-key-1")
            assertThat(keyId).isNotEqualTo("default")
        }
    }

    private fun request(approvalId: String): ApprovalGatewayPersistenceRequest {
        val requestedAt = BASE_NOW
        val expiresAt = BASE_NOW.plusSeconds(600)
        val workflowDigest = digest('b')
        val argumentsDigest = digest('a')
        val approvalTokenDigest = digest('c')
        val messages =
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
        val operationReference = ResumeOperationReference("t.Service", "approve", "(Ljava/lang/String;)V", digest('d'))
        val replayDigest = ReplayEnvelopeDigestHelper.compute(operationReference, messages)

        return ApprovalGatewayPersistenceRequest(
            approvalRequest =
                ApprovalRequest(
                    approvalId = approvalId,
                    binding =
                        ApprovalBinding(
                            workflowRunId = "wf-$approvalId",
                            toolName = "tool-$approvalId",
                            argumentsDigest = argumentsDigest,
                            policyVersion = "policy-v1",
                            workflowDigest = workflowDigest,
                            approvalTokenDigest = approvalTokenDigest,
                        ),
                    status = ApprovalStatus.PENDING,
                    requestedBy = "requestor-$approvalId",
                    requestedAt = requestedAt,
                    expiresAt = expiresAt,
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
                    workflowRunId = "wf-$approvalId",
                    correlationId = "corr-$approvalId",
                    toolCallId = "tool-call-$approvalId",
                    toolName = "tool-$approvalId",
                    argumentsDigest = argumentsDigest,
                    policyVersion = "policy-v1",
                    workflowDigest = workflowDigest,
                    status = ApprovalContinuationStatus.PENDING,
                    createdAt = requestedAt,
                    approvalExpiresAt = expiresAt,
                    claimedBy = null,
                    claimedAt = null,
                    completedAt = null,
                    version = 0L,
                ),
            sensitiveArguments = SensitiveToolArguments.of("""{"claimId":"$approvalId"}"""),
            suspendedInvocationMetadata =
                SuspendedInvocationMetadata(
                    approvalId = approvalId,
                    toolCallId = "tool-call-$approvalId",
                    toolName = "tool-$approvalId",
                    toolCallIndex = 0,
                    correlationId = "corr-$approvalId",
                    identity =
                        EngineExecutionIdentity(
                            workflowRunId = "wf-$approvalId",
                            correlationId = "corr-$approvalId",
                            workflowDigest = workflowDigest,
                            policyVersion = "policy-v1",
                            actorId = "requestor-$approvalId",
                        ),
                    securityContext = ExecutionSecurityContext(),
                    operationReference = operationReference,
                    replayEnvelopeDigest = replayDigest,
                    toolReference = ResumeToolReference("tool-$approvalId", digest('e')),
                ),
            replayEnvelope = SensitiveReplayEnvelope.of(messages),
            resumeToken = ResumeToken("resume-$approvalId"),
        )
    }

    private fun auditIntent(
        approvalId: String,
        eventKey: String,
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = UUID.randomUUID().toString(),
            aggregateIdDigest = sha256Hex(approvalId),
            eventKey = eventKey,
            actor = "system",
            workflowRunId = "wf-$approvalId",
            correlationId = "corr-$approvalId",
            approvalStatus = "PENDING",
            approvalVersion = 0L,
            reasonDigest = sha256Hex("approval-requested"),
            reasonLength = "approval-requested".length,
            createdAt = BASE_NOW,
            status = SovereignOpsAuditOutboxStatus.PREPARED,
        )

    private fun digest(ch: Char): Sha256Digest = Sha256Digest.of("sha256:${ch.toString().repeat(64)}")

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

    private fun truncateTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val truncateAll =
                    "TRUNCATE TABLE approval_continuations, suspended_invocations, audit_outbox, approvals CASCADE"
                stmt.execute(truncateAll)
            }
        }
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

    private fun assertThatSuspendCallThrows(block: suspend () -> Unit) =
        assertThatThrownBy {
            runBlocking {
                block()
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

    private fun selectValue(
        sql: String,
        value: String,
    ): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, value)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return "sha256:${hash.joinToString("") { "%02x".format(it) }}"
    }
}

private fun unsupported(): Nothing = throw UnsupportedOperationException()
