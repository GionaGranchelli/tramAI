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
import dev.tramai.core.approval.gateway.ResumeToken
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
import dev.tramai.engine.ReplayEnvelopeDigestHelper
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
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
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationResult
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

@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsGovernedApprovalRequestMutationStoreTest {
    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres =
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("sovereign_ops_governed_mutation_test")
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
    private val clockAt: Clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC)

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
                clock = clockAt,
            )
        approvalStore = JdbcApprovalStore(dataSource = dataSource, clock = clockAt)
        suspendedInvocationStore =
            JdbcSuspendedInvocationStore(
                dataSource = dataSource,
                replayEnvelopeCodec = replayCodec,
                clock = clockAt,
            )
        continuationStore =
            JdbcApprovalContinuationStore(
                dataSource = dataSource,
                argumentsCodec = continuationCodec,
                clock = clockAt,
            )
    }

    // -----------------------------------------------------------------------------------------
    // 0.7.1d1 P3 fixtures. Built in small parts on purpose: the legacy suite's single request()
    // builder is baselined under its own declaration identity, so this suite constructs its own
    // equivalent request instead of moving that function.
    // -----------------------------------------------------------------------------------------

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

    @Test
    fun `governed creation writes the reserved attribution and the canonical identity in one transaction`() {
        runBlocking {
            val approvalId = "approval-governed-a"
            val identity = identity("wf-$approvalId")

            val result = mutationStore.createGovernedApprovalRequest(request(approvalId), identity)

            assertThat(result).isEqualTo(
                SovereignOpsApprovalRequestMutationResult.Created(
                    approvalId = approvalId,
                    correlationId = "corr-$approvalId",
                    resumeToken = ResumeToken("resume-$approvalId"),
                ),
            )

            // Approval row: the five reserved components, and no run id of its own.
            val attribution = metadataNode(approvalId)["attribution"]
            assertThat(attribution).isNotNull
            ApprovalAttributionKeys.ALL.forEach { key ->
                assertThat(attribution!![key]?.asText()).isNotBlank
            }

            // Suspension row: the SAME canonical identity, proven by reading it back through the
            // canonical governed reader (exact equality of all six components, not just the run id).
            val canonicalReader = GovernedJdbcSuspendedInvocationStore(delegate = suspendedInvocationStore)
            assertThat(canonicalReader.governedRunIdentity(approvalId)).isEqualTo(identity)
            assertThat(attribution!!.size()).isEqualTo(ApprovalAttributionKeys.ALL.size)
            assertThat(attribution.has(identity.runId.value)).isFalse

            // Suspension: decrypted V2 payload carrying the full canonical identity.
            val payload = decryptedSuspension(approvalId)
            assertThat(payload["payloadVersion"].asInt()).isEqualTo(2)
            val persisted = payload["governedRunIdentity"]
            assertThat(persisted["runId"].asText()).isEqualTo(identity.runId.value)
            assertThat(persisted["workloadId"].asText()).isEqualTo(identity.deployment.workloadId.value)
            assertThat(persisted["deploymentId"].asText()).isEqualTo(identity.deployment.deploymentId.value)
            assertThat(suspendedInvocationStore.get(approvalId)).isNotNull
        }
    }

    @Test
    fun `ungoverned creation metadata keeps the released shape`() {
        runBlocking {
            val approvalId = "approval-legacy-shape"

            mutationStore.createApprovalRequest(request(approvalId))

            // No attribution block at all: not an empty object, not null.
            val metadata = metadataNode(approvalId)
            assertThat(metadata.has("attribution")).isFalse
            ApprovalAttributionKeys.ALL.forEach { key ->
                assertThat(metadata.has(key)).isFalse
            }
            // And the suspension is a V1 payload: neither version nor identity is present.
            val payload = decryptedSuspension(approvalId)
            assertThat(payload.has("payloadVersion")).isFalse
            assertThat(payload.has("governedRunIdentity")).isFalse
        }
    }

    @Test
    fun `governed creation rejects a request bound to a different run with zero writes`() {
        runBlocking {
            val approvalId = "approval-governed-mismatch"

            val store = mutationStore
            val approvalRequest = request(approvalId)
            val mismatchedIdentity = identity("elsewhere")
            val thrown =
                assertThatSuspendCallThrows {
                    store.createGovernedApprovalRequest(approvalRequest, mismatchedIdentity)
                }
            thrown.isInstanceOf(GovernedRunContinuityException::class.java)

            assertThat(approvalStore.get(approvalId)).isNull()
            assertThat(suspendedInvocationStore.get(approvalId)).isNull()
            assertThat(selectCount("SELECT count(*) FROM approvals")).isZero()
        }
    }

    @Test
    fun `governed creation rejects suspension metadata naming another run with zero writes`() {
        runBlocking {
            val approvalId = "approval-carrier-suspension"
            val identity = identity("wf-$approvalId")
            val base = request(approvalId)
            val request =
                base.copy(
                    suspendedInvocationMetadata =
                        base.suspendedInvocationMetadata.copy(
                            identity =
                                base.suspendedInvocationMetadata.identity.copy(
                                    workflowRunId = "wf-other",
                                ),
                        ),
                )

            val thrown =
                assertThatSuspendCallThrows {
                    mutationStore.createGovernedApprovalRequest(request, identity)
                }
            thrown.isInstanceOf(GovernedRunContinuityException::class.java)
            thrown.hasMessageContaining("suspended invocation metadata")

            assertThat(approvalStore.get(approvalId)).isNull()
            assertThat(suspendedInvocationStore.get(approvalId)).isNull()
            assertThat(continuationStore.get(approvalId)).isNull()
            assertThat(selectCount("SELECT count(*) FROM approvals")).isZero()
            assertThat(selectCount("SELECT count(*) FROM suspended_invocations")).isZero()
            assertThat(selectCount("SELECT count(*) FROM approval_continuations")).isZero()
        }
    }

    @Test
    fun `governed creation rejects a continuation naming another run with zero writes`() {
        runBlocking {
            val approvalId = "approval-carrier-continuation"
            val identity = identity("wf-$approvalId")
            val base = request(approvalId)
            val request = base.copy(continuation = base.continuation.copy(workflowRunId = "wf-other"))

            val thrown =
                assertThatSuspendCallThrows {
                    mutationStore.createGovernedApprovalRequest(request, identity)
                }
            thrown.isInstanceOf(GovernedRunContinuityException::class.java)
            thrown.hasMessageContaining("continuation")

            assertThat(selectCount("SELECT count(*) FROM approvals")).isZero()
            assertThat(selectCount("SELECT count(*) FROM suspended_invocations")).isZero()
            assertThat(selectCount("SELECT count(*) FROM approval_continuations")).isZero()
        }
    }

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

    private fun metadataNode(approvalId: String): JsonNode =
        mapper.readTree(
            checkNotNull(selectValue("SELECT sanitized_metadata FROM approvals WHERE approval_id = ?", approvalId)),
        )

    /** Decrypted suspension payload as a JSON tree: the only way to see V1/V2 and the identity. */

    private fun decryptedSuspension(approvalId: String): JsonNode {
        val columns =
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        "SELECT encrypted_replay_envelope, encryption_nonce, encryption_algorithm " +
                            "FROM suspended_invocations WHERE invocation_id = ?",
                    ).use { stmt ->
                        stmt.setString(1, approvalId)
                        stmt.executeQuery().use { rs ->
                            check(rs.next())
                            Triple(rs.getBytes(1), rs.getBytes(2), rs.getString(3))
                        }
                    }
            }
        return mapper.readTree(decrypt(columns.first, columns.second, columns.third))
    }

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
                stmt.execute(
                    "TRUNCATE TABLE approval_continuations, suspended_invocations, audit_outbox, approvals CASCADE",
                )
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
    // 0.7.1d1 P3: governed transactional creation. The approval-row attribution and the suspension
    // identity must come from ONE canonical identity inside the SAME transaction, and an existing row
    // may only ever be adopted by the run it is durably attributed to.
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
}
