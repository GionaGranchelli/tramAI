package dev.tramai.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.TokenBudgetSnapshot
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * JDBC-backed [SuspendedInvocationStore] implementation using the `suspended_invocations`
 * table in PostgreSQL.
 *
 * ## Data model
 * The store serialises both [SuspendedInvocationMetadata] and the replay-envelope
 * [Message]s as a single JSON payload, then encrypts it via [JdbcReplayEnvelopeCodec].
 * The ciphertext and codec metadata are stored in the `encrypted_replay_envelope`,
 * `encryption_key_id`, `encryption_algorithm`, `encryption_nonce`, and
 * `payload_digest` columns. Non-sensitive fields (`service_key`, `operation_key`,
 * `descriptor_hash`, `replay_envelope_digest`) are stored in dedicated columns
 * for queryability and duplicate detection.
 *
 * ## Security
 * - No raw tool arguments, prompts, model responses, or sensitive payloads are
 *   stored in plaintext — the full replay envelope is always encrypted.
 * - The replay-envelope digest is stored in a plaintext column with a unique
 *   index to prevent double-suspension of the same invocation.
 * - The codec is injected by the caller, so key-management is outside this store.
 *
 * ## Concurrency
 * Operations run under default JDBC autocommit. The unique constraints on
 * `invocation_id` (PK) and `replay_envelope_digest` (unique index) provide
 * atomic duplicate detection.
 *
 * @param dataSource The [DataSource] providing connections to PostgreSQL.
 * @param replayEnvelopeCodec The codec used to encrypt/decrypt replay payloads.
 * @param clock The clock for timestamp generation.
 */
class JdbcSuspendedInvocationStore(
    private val dataSource: DataSource,
    private val replayEnvelopeCodec: JdbcReplayEnvelopeCodec,
    private val clock: Clock = Clock.systemUTC(),
) : SuspendedInvocationStore {

    /**
     * ObjectMapper used exclusively for serialising the combined
     * metadata + message payload that goes into [encrypted_replay_envelope].
     * Default typing is enabled to handle sealed/interface types
     * ([ContentPart], [Message], etc.) correctly.
     */
    private val payloadMapper: ObjectMapper = JsonMapper.builder()
        .addModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .activateDefaultTyping(
            BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Any::class.java)
                .build(),
            ObjectMapper.DefaultTyping.NON_FINAL,
        )
        .build()
        .registerKotlinModule()

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        val safeId = validateIdField(metadata.approvalId, "approvalId")
        validateIdField(metadata.toolCallId, "toolCallId")
        validateIdField(metadata.toolName, "toolName")
        validateIdField(metadata.correlationId, "correlationId")
        metadata.conversationId?.let { validateIdField(it, "conversationId") }

        val messages = replayEnvelope.revealForResume().messages

        val payload = Payload(
            metadata = PayloadMetadata.fromDomain(metadata),
            messages = messages,
        )
        val payloadJson = payloadMapper.writeValueAsBytes(payload)

        val encrypted = replayEnvelopeCodec.encode(payloadJson)

        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

        validateDigestField(metadata.replayEnvelopeDigest.value)

        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO suspended_invocations (
                    invocation_id, status, service_key, operation_key, descriptor_hash,
                    replay_envelope_digest, encrypted_replay_envelope,
                    encryption_key_id, encryption_algorithm, encryption_nonce, payload_digest,
                    version, created_at
                ) VALUES (
                    ?, 'PENDING', ?, ?, ?,
                    ?, ?,
                    ?, ?, ?, ?,
                    1, ?
                )
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, metadata.approvalId)
                stmt.setString(2, metadata.operationReference.serviceInterface)
                stmt.setString(3, metadata.operationReference.methodName)
                stmt.setString(4, metadata.operationReference.resumeDefinitionDigest.value)
                stmt.setString(5, metadata.replayEnvelopeDigest.value)
                stmt.setBytes(6, encrypted.ciphertext)
                stmt.setString(7, encrypted.keyId)
                stmt.setString(8, encrypted.algorithm)
                stmt.setBytes(9, encrypted.nonce)
                stmt.setString(10, encrypted.payloadDigest)
                stmt.setObject(11, now)

                try {
                    stmt.executeUpdate()
                } catch (e: SQLException) {
                    if (e.sqlState == "23505") {
                        val msg = e.message ?: ""
                        if (msg.contains("replay_envelope_digest", ignoreCase = true)) {
                            throw IllegalArgumentException(
                                "suspended-invocation-replay-envelope-digest-already-exists",
                            )
                        }
                        throw IllegalArgumentException(
                            "suspended-invocation-already-exists",
                        )
                    }
                    throw e
                }
            }
        }
    }

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? {
        validateIdField(approvalId, "approvalId")

        val row = readCurrent(approvalId) ?: return null
        return row.metadata.toDomain()
    }

    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? {
        validateIdField(approvalId, "approvalId")

        val row = readCurrent(approvalId) ?: return null
        return SensitiveReplayEnvelope.of(row.messages)
    }

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
        validateIdField(approvalId, "approvalId")

        // Read + decrypt before deleting
        val row = readCurrent(approvalId) ?: return null

        dataSource.connection.use { conn ->
            val sql = "DELETE FROM suspended_invocations WHERE invocation_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.executeUpdate()
            }
        }

        return row.metadata.toDomain()
    }

    // ── Internal helpers ──────────────────────────────────────────

    private data class DecryptedRow(
        val metadata: PayloadMetadata,
        val messages: List<Message>,
        val version: Long,
    )

    /**
     * Reads one row from [suspended_invocations] by [approvalId],
     * decrypts the payload, and returns the deserialised result.
     * Returns null when no row exists.
     */
    private fun readCurrent(approvalId: String): DecryptedRow? {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT encrypted_replay_envelope, encryption_key_id, encryption_algorithm,
                       encryption_nonce, payload_digest, version
                FROM suspended_invocations
                WHERE invocation_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null

                    val ciphertext = rs.getBytes("encrypted_replay_envelope")
                        ?: throw IllegalStateException("suspended-invocation-corrupted: encrypted_replay_envelope is null")
                    val keyId = rs.getString("encryption_key_id")
                        ?: throw IllegalStateException("suspended-invocation-corrupted: encryption_key_id is null")
                    val algorithm = rs.getString("encryption_algorithm")
                        ?: throw IllegalStateException("suspended-invocation-corrupted: encryption_algorithm is null")
                    val nonce = rs.getBytes("encryption_nonce")
                        ?: throw IllegalStateException("suspended-invocation-corrupted: encryption_nonce is null")
                    val payloadDigest = rs.getString("payload_digest")
                        ?: throw IllegalStateException("suspended-invocation-corrupted: payload_digest is null")
                    val version = rs.getLong("version")

                    val encrypted = JdbcEncryptedReplayEnvelope(
                        ciphertext = ciphertext,
                        keyId = keyId,
                        algorithm = algorithm,
                        nonce = nonce,
                        payloadDigest = payloadDigest,
                    )

                    val plaintext = try {
                        replayEnvelopeCodec.decode(encrypted)
                    } catch (e: Exception) {
                        throw IllegalStateException("suspended-invocation-decryption-failed", e)
                    }

                    val payload: Payload = try {
                        payloadMapper.readValue(plaintext)
                    } catch (e: Exception) {
                        throw IllegalStateException("suspended-invocation-deserialization-failed", e)
                    }

                    return DecryptedRow(
                        metadata = payload.metadata,
                        messages = payload.messages,
                        version = version,
                    )
                }
            }
        }
    }

    private fun validateIdField(value: String, fieldName: String): String {
        require(value.isNotBlank()) { "$fieldName must not be blank" }
        require(value.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(value.length <= 256) { "$fieldName exceeds maximum length of 256" }
        require(value == value.trim()) { "$fieldName must not contain surrounding whitespace" }
        return value
    }

    private fun validateDigestField(value: String) {
        require(value.startsWith("sha256:")) { "replayEnvelopeDigest must start with 'sha256:'" }
        require(value.length == 71) { "replayEnvelopeDigest must be 71 characters (sha256: + 64 hex chars)" }
    }

    // ── Internal data types for serialisation ─────────────────────

    /**
     * Combined payload that is serialised as JSON, then encrypted.
     */
    private data class Payload(
        val metadata: PayloadMetadata,
        val messages: List<Message>,
    )

    /**
     * Persistable snapshot of [SuspendedInvocationMetadata].
     * All fields are serialisable without Jackson default-typing.
     */
    private data class PayloadMetadata(
        val approvalId: String,
        val toolCallId: String,
        val toolName: String,
        val toolCallIndex: Int,
        val correlationId: String,
        val identityWorkflowRunId: String,
        val identityCorrelationId: String,
        val identityWorkflowDigest: String,
        val identityPolicyVersion: String,
        val identityActorId: String,
        val securityDataClassification: String?,
        val securityClassificationSource: String?,
        val operationServiceInterface: String,
        val operationMethodName: String,
        val operationJvmMethodDescriptor: String,
        val operationResumeDefinitionDigest: String,
        val replayEnvelopeDigest: String,
        val conversationId: String?,
        val historySize: Int,
        val tokenBudgetTotalInputTokens: Long?,
        val tokenBudgetTotalOutputTokens: Long?,
        val tokenBudgetTotalInputCost: Double?,
        val tokenBudgetTotalOutputCost: Double?,
        val tokenBudgetWarnIfExceeded: Boolean?,
        val toolReferenceName: String,
        val toolReferenceDeclarationDigest: String,
        val toolSecurity: String?,
    ) {
        companion object {
            private val mapper: ObjectMapper = ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

            fun fromDomain(metadata: SuspendedInvocationMetadata): PayloadMetadata = PayloadMetadata(
                approvalId = metadata.approvalId,
                toolCallId = metadata.toolCallId,
                toolName = metadata.toolName,
                toolCallIndex = metadata.toolCallIndex,
                correlationId = metadata.correlationId,
                identityWorkflowRunId = metadata.identity.workflowRunId,
                identityCorrelationId = metadata.identity.correlationId,
                identityWorkflowDigest = metadata.identity.workflowDigest.value,
                identityPolicyVersion = metadata.identity.policyVersion,
                identityActorId = metadata.identity.actorId,
                securityDataClassification = metadata.securityContext.dataClassification?.name,
                securityClassificationSource = metadata.securityContext.classificationSource?.name,
                operationServiceInterface = metadata.operationReference.serviceInterface,
                operationMethodName = metadata.operationReference.methodName,
                operationJvmMethodDescriptor = metadata.operationReference.jvmMethodDescriptor,
                operationResumeDefinitionDigest = metadata.operationReference.resumeDefinitionDigest.value,
                replayEnvelopeDigest = metadata.replayEnvelopeDigest.value,
                conversationId = metadata.conversationId,
                historySize = metadata.historySize,
                tokenBudgetTotalInputTokens = metadata.tokenBudgetSnapshot?.totalInputTokens,
                tokenBudgetTotalOutputTokens = metadata.tokenBudgetSnapshot?.totalOutputTokens,
                tokenBudgetTotalInputCost = metadata.tokenBudgetSnapshot?.totalInputCost,
                tokenBudgetTotalOutputCost = metadata.tokenBudgetSnapshot?.totalOutputCost,
                tokenBudgetWarnIfExceeded = metadata.tokenBudgetSnapshot?.warnIfExceeded,
                toolReferenceName = metadata.toolReference.toolName,
                toolReferenceDeclarationDigest = metadata.toolReference.declarationDigest.value,
                toolSecurity = metadata.toolSecurity?.let { mapper.writeValueAsString(it) },
            )
        }

        fun toDomain(): SuspendedInvocationMetadata = SuspendedInvocationMetadata(
            approvalId = approvalId,
            toolCallId = toolCallId,
            toolName = toolName,
            toolCallIndex = toolCallIndex,
            correlationId = correlationId,
            identity = EngineExecutionIdentity(
                workflowRunId = identityWorkflowRunId,
                correlationId = identityCorrelationId,
                workflowDigest = Sha256Digest.of(identityWorkflowDigest),
                policyVersion = identityPolicyVersion,
                actorId = identityActorId,
            ),
            securityContext = ExecutionSecurityContext(
                dataClassification = securityDataClassification?.let { enumValueOf(it) },
                classificationSource = securityClassificationSource?.let { enumValueOf(it) },
            ),
            operationReference = ResumeOperationReference(
                serviceInterface = operationServiceInterface,
                methodName = operationMethodName,
                jvmMethodDescriptor = operationJvmMethodDescriptor,
                resumeDefinitionDigest = Sha256Digest.of(operationResumeDefinitionDigest),
            ),
            replayEnvelopeDigest = Sha256Digest.of(replayEnvelopeDigest),
            conversationId = conversationId,
            historySize = historySize,
            tokenBudgetSnapshot = tokenBudgetTotalInputTokens?.let { inputTokens ->
                TokenBudgetSnapshot(
                    totalInputTokens = inputTokens,
                    totalOutputTokens = tokenBudgetTotalOutputTokens ?: 0L,
                    totalInputCost = tokenBudgetTotalInputCost ?: 0.0,
                    totalOutputCost = tokenBudgetTotalOutputCost ?: 0.0,
                    warnIfExceeded = tokenBudgetWarnIfExceeded ?: false,
                )
            },
            toolReference = ResumeToolReference(
                toolName = toolReferenceName,
                declarationDigest = Sha256Digest.of(toolReferenceDeclarationDigest),
            ),
            toolSecurity = toolSecurity?.let { mapper.readValue(it) },
        )
    }
}
