package dev.tramai.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.Sha256Digest
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
 * Messages are serialized via explicit [PersistedMessage]/[PersistedToolCall] DTOs,
 * avoiding Jackson polymorphic typing. Invariant validation (tool call ID/name/index)
 * and canonical digest verification are performed before persistence — the store
 * recomputes [SuspendedInvocationMetadata.replayEnvelopeDigest] from the actual
 * messages and fails closed on mismatch.
 *
 * ## Security
 * - No raw tool arguments, prompts, model responses, or sensitive payloads are
 *   stored in plaintext — the full replay envelope is always encrypted.
 * - The replay-envelope digest is stored in a plaintext column with a unique
 *   index to prevent double-suspension of the same invocation.
 * - Safe actor IDs (workflowRunId, actorId, etc.) are stored inside the encrypted
 *   payload, not in plaintext columns.
 * - The codec is injected by the caller, so key-management is outside this store.
 *
 * ## Concurrency
 * - [create] relies on PostgreSQL unique constraints (PK + unique index) for
 *   atomic duplicate detection.
 * - [remove] reads and deletes within one explicit transaction, using
 *   `SELECT ... FOR UPDATE` to prevent concurrent double-consumption.
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
     * Plain ObjectMapper for JSONB-safe metadata serialization (toolSecurity).
     * No default typing — only used for safe primitive/String fields.
     */
    companion object {
        private val mapper: ObjectMapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        validateIdField(metadata.approvalId, "approvalId")
        validateIdField(metadata.toolCallId, "toolCallId")
        validateIdField(metadata.toolName, "toolName")
        validateIdField(metadata.correlationId, "correlationId")
        metadata.conversationId?.let { validateIdField(it, "conversationId") }
        validateDigestField(metadata.replayEnvelopeDigest.value)

        // Extract messages and validate replay-envelope invariants
        val messages = replayEnvelope.revealForResume().messages
        validateReplayEnvelopeInvariants(metadata, messages)

        // Serialize via explicit DTOs — no polymorphic typing
        val pm = messages.map { toPersisted(it) }

        // Verify the caller-provided digest matches the canonical digest of the
        // persisted replay envelope. This prevents callers from using a wrong
        // digest and bypassing duplicate detection.
        val canonicalDigest = ReplayEnvelopeDigestHelper.compute(metadata.operationReference, messages)
        require(canonicalDigest == metadata.replayEnvelopeDigest) {
            "replay-envelope-digest-mismatch: canonical=$canonicalDigest, provided=${metadata.replayEnvelopeDigest}"
        }

        val payload = Payload(
            metadata = PayloadMetadata.fromDomain(metadata),
            persistedMessages = pm,
        )
        val payloadJson = mapper.writeValueAsBytes(payload)

        val encrypted = replayEnvelopeCodec.encode(payloadJson)

        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

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
                        // Check which constraint fired
                        val constraintName = extractConstraintName(e)
                        if (constraintName != null) {
                            when {
                                constraintName.contains("replay_envelope", ignoreCase = true) ->
                                    throw IllegalArgumentException(
                                        "suspended-invocation-replay-envelope-digest-already-exists",
                                    )
                                else ->
                                    throw IllegalArgumentException(
                                        "suspended-invocation-already-exists",
                                    )
                            }
                        }
                        // Fallback: check PK existence to distinguish
                        if (invocationExists(conn, metadata.approvalId)) {
                            throw IllegalArgumentException("suspended-invocation-already-exists")
                        }
                        throw IllegalArgumentException(
                            "suspended-invocation-replay-envelope-digest-already-exists",
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

        // Read + delete inside one explicit transaction with row lock
        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val sql = """
                    SELECT encrypted_replay_envelope, encryption_key_id, encryption_algorithm,
                           encryption_nonce, payload_digest, version
                    FROM suspended_invocations
                    WHERE invocation_id = ?
                    FOR UPDATE
                """.trimIndent()
                val row = conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, approvalId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            conn.rollback()
                            return@remove null
                        }

                        val encrypted = readEncryptedFromRow(rs)
                        val v = rs.getLong("version")
                        val payload = decryptAndDeserialize(encrypted)
                        val domainMessages = payload.persistedMessages.map { toDomainMessage(it) }
                        Triple(encrypted, v, PayloadWithDomainMessages(payload.metadata, domainMessages))
                    }
                }
                val (_, _, payloadWithMessages) = row

                val deleteSql = "DELETE FROM suspended_invocations WHERE invocation_id = ?"
                conn.prepareStatement(deleteSql).use { stmt ->
                    stmt.setString(1, approvalId)
                    stmt.executeUpdate()
                }

                conn.commit()
                return payloadWithMessages.metadata.toDomain()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────

    /**
     * Validates invariants between the [SuspendedInvocationMetadata] and the
     * replay envelope [Message]s that will be persisted:
     * - Tool call ID, name, and index in the messages must match metadata.
     */
    private fun validateReplayEnvelopeInvariants(
        metadata: SuspendedInvocationMetadata,
        messages: List<Message>,
    ) {
        // Find the matching assistant message with tool calls
        val assistantMsg = messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.toolCalls.isNullOrEmpty() }
            ?: throw IllegalArgumentException("replay-envelope-no-assistant-tool-calls")

        val tc = checkNotNull(assistantMsg.toolCalls)

        require(metadata.toolCallIndex in tc.indices) { "replay-envelope-tool-call-index-out-of-bounds" }
        val selectedCall = tc[metadata.toolCallIndex]
        require(selectedCall.id == metadata.toolCallId) { "replay-envelope-tool-call-id-mismatch" }
        require(selectedCall.name == metadata.toolName) { "replay-envelope-tool-call-name-mismatch" }
    }

    /**
     * Extracts the constraint name from a [SQLException] message text.
     * Returns null when the constraint name cannot be determined.
     * Handles PostgreSQL error format: "duplicate key value violates unique constraint \"name\"".
     */
    private fun extractConstraintName(e: SQLException): String? {
        // Parse constraint name from the PostgreSQL error message
        // Pattern: "duplicate key value violates unique constraint "uq_name""
        // or: "Key (column)=(value) already exists."
        val msg = e.message ?: return null
        val constraintMatch = Regex("""constraint "?([^"\s]+)"?""").find(msg)
        return constraintMatch?.groupValues?.getOrNull(1)
    }

    /**
     * Checks whether a row with the given [approvalId] exists in the table.
     * Uses the same connection to stay within the existing transaction context.
     */
    private fun invocationExists(conn: java.sql.Connection, approvalId: String): Boolean {
        val sql = "SELECT 1 FROM suspended_invocations WHERE invocation_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, approvalId)
            stmt.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

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

                    val encrypted = readEncryptedFromRow(rs)
                    val version = rs.getLong("version")
                    val payload = decryptAndDeserialize(encrypted)

                    return DecryptedRow(
                        metadata = payload.metadata,
                        messages = payload.persistedMessages.map { toDomainMessage(it) },
                        version = version,
                    )
                }
            }
        }
    }

    private data class DecryptedRow(
        val metadata: PayloadMetadata,
        val messages: List<Message>,
        val version: Long,
    )

    private fun readEncryptedFromRow(rs: ResultSet): JdbcEncryptedReplayEnvelope {
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

        return JdbcEncryptedReplayEnvelope(
            ciphertext = ciphertext,
            keyId = keyId,
            algorithm = algorithm,
            nonce = nonce,
            payloadDigest = payloadDigest,
        )
    }

    private fun decryptAndDeserialize(encrypted: JdbcEncryptedReplayEnvelope): Payload {
        val plaintext = try {
            replayEnvelopeCodec.decode(encrypted)
        } catch (e: Exception) {
            throw IllegalStateException("suspended-invocation-decryption-failed", e)
        }

        return try {
            mapper.readValue(plaintext)
        } catch (e: Exception) {
            throw IllegalStateException("suspended-invocation-deserialization-failed", e)
        }
    }

    private fun validateIdField(value: String, fieldName: String) {
        require(value.isNotBlank()) { "$fieldName must not be blank" }
        require(value.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(value.length <= 256) { "$fieldName exceeds maximum length of 256" }
        require(value == value.trim()) { "$fieldName must not contain surrounding whitespace" }
    }

    private fun validateDigestField(value: String) {
        require(value.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
            "replayEnvelopeDigest must match sha256: followed by 64 hex characters"
        }
    }

    // ── Explicit DTO serialization (no polymorphic typing) ─────────

    private data class PayloadWithDomainMessages(
        val metadata: PayloadMetadata,
        val messages: List<Message>,
    )

    private fun toDomainMessage(pm: PersistedMessage): Message = Message(
        role = MessageRole.valueOf(pm.role),
        content = pm.content,
        toolCalls = pm.toolCalls?.map { toDomainToolCall(it) },
    )

    private fun toDomainToolCall(ptc: PersistedToolCall): ToolCall = ToolCall(
        id = ptc.id,
        name = ptc.name,
        argumentsJson = ptc.argumentsJson,
    )

    /**
     * Persistable snapshot of a [Message] — no Jackson default typing needed.
     */
    private data class PersistedMessage(
        val role: String,
        val content: String,
        val toolCalls: List<PersistedToolCall>?,
    )

    /**
     * Persistable snapshot of a [ToolCall] — no Jackson default typing needed.
     */
    private data class PersistedToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
    )

    private fun toPersisted(msg: Message): PersistedMessage = PersistedMessage(
        role = msg.role.name,
        content = msg.content,
        toolCalls = msg.toolCalls?.map { toPersistedToolCall(it) },
    )

    private fun toPersistedToolCall(tc: ToolCall): PersistedToolCall = PersistedToolCall(
        id = tc.id,
        name = tc.name,
        argumentsJson = tc.argumentsJson,
    )

    // ── Internal data types for serialisation ─────────────────────

    /**
     * Combined payload that is serialised as JSON, then encrypted.
     * Uses PersistedMessage DTOs — no Jackson default typing.
     */
    private data class Payload(
        val metadata: PayloadMetadata,
        val persistedMessages: List<PersistedMessage>,
    )

    /**
     * Persistable snapshot of [SuspendedInvocationMetadata].
     * All fields are plain Strings/numbers — no Jackson default typing.
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
