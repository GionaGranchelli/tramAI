package dev.tramai.persistence.jdbc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.coroutines.rethrowIfCancellation
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
import dev.tramai.engine.GovernedSuspendedInvocationStore
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
        private const val REDACTED_APPROVAL_CONTINUATION_ARGUMENTS =
            "__redacted_approval_continuation_args__"

        private val mapper: ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ): Unit =
        withSafeJdbc({ "Database operation failed for suspended invocation: ${metadata.approvalId}" }) {
            validateCreateInput(metadata)
            val payloadJson = buildCreatePayload(metadata, replayEnvelope, runIdentity = null)
            val encrypted = replayEnvelopeCodec.encode(payloadJson)
            val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

            dataSource.connection.use { conn ->
                insertSuspendedInvocation(conn, metadata, encrypted, now)
            }
        }

    /**
     * Persists a GOVERNED suspension: the same single row and single insert as [create], with
     * the canonical run identity inside the same encrypted payload. The identity is therefore
     * never promoted into plaintext columns and a crash can never leave a half-governed record.
     */
    internal suspend fun createGoverned(
        suspended: GovernedSuspendedInvocation,
        replayEnvelope: SensitiveReplayEnvelope,
    ): Unit =
        withSafeJdbc({
            "Database operation failed for suspended invocation: ${suspended.metadata.approvalId}"
        }) {
            validateCreateInput(suspended.metadata)
            val payloadJson =
                buildCreatePayload(suspended.metadata, replayEnvelope, suspended.runIdentity)
            val encrypted = replayEnvelopeCodec.encode(payloadJson)
            val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

            dataSource.connection.use { conn ->
                insertSuspendedInvocation(conn, suspended.metadata, encrypted, now)
            }
        }

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? =
        withSafeJdbc({ "Database operation failed for suspended invocation: $approvalId" }) {
            validateIdField(approvalId, "approvalId")

            val row = readCurrent(approvalId) ?: return@withSafeJdbc null
            row.metadata.toDomain()
        }

    internal suspend fun governedRunIdentity(approvalId: String): GovernedRunIdentity? =
        withSafeJdbc({ "Database operation failed for suspended invocation: $approvalId" }) {
            validateIdField(approvalId, "approvalId")

            val row = readCurrent(approvalId) ?: return@withSafeJdbc null
            row.governedRunIdentity
        }

    /**
     * Validates non-sensitive create fields before any replay payload is serialized.
     */
    private fun validateCreateInput(metadata: SuspendedInvocationMetadata) {
        validateIdField(metadata.approvalId, "approvalId")
        validateIdField(metadata.toolCallId, "toolCallId")
        validateIdField(metadata.toolName, "toolName")
        validateIdField(metadata.correlationId, "correlationId")
        metadata.conversationId?.let { validateIdField(it, "conversationId") }
        validateDigestField(metadata.replayEnvelopeDigest.value)
    }

    /**
     * Serializes the validated replay payload into the encrypted JDBC payload format.
     */
    private fun buildCreatePayload(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
        runIdentity: GovernedRunIdentity?,
    ): ByteArray {
        val messages = replayEnvelope.revealForResume().messages
        validateReplayEnvelopeInvariants(metadata, messages)
        validateReplayEnvelopeDigest(metadata, messages)
        val payload =
            Payload(
                payloadVersion = if (runIdentity == null) 1 else 2,
                metadata = PayloadMetadata.fromDomain(metadata),
                persistedMessages = messages.map { toPersisted(it) },
                governedRunIdentity = runIdentity?.let { PayloadGovernedRunIdentity.fromDomain(it) },
            )
        return mapper.writeValueAsBytes(payload)
    }

    /**
     * Verifies the caller-provided replay digest against canonical message content.
     */
    private fun validateReplayEnvelopeDigest(
        metadata: SuspendedInvocationMetadata,
        messages: List<Message>,
    ) {
        val canonicalDigest = ReplayEnvelopeDigestHelper.compute(metadata.operationReference, messages)
        require(canonicalDigest == metadata.replayEnvelopeDigest) {
            "replay-envelope-digest-mismatch: canonical=$canonicalDigest, provided=${metadata.replayEnvelopeDigest}"
        }
    }

    /**
     * Inserts a suspended invocation row and maps duplicate constraints to stable error codes.
     */
    private fun insertSuspendedInvocation(
        conn: java.sql.Connection,
        metadata: SuspendedInvocationMetadata,
        encrypted: JdbcEncryptedReplayEnvelope,
        now: OffsetDateTime,
    ) {
        val sql =
            """
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
                handleCreateConflict(conn, metadata.approvalId, e)
            }
        }
    }

    /**
     * Converts PostgreSQL unique-constraint failures into store-level reason codes.
     */
    private fun handleCreateConflict(
        conn: java.sql.Connection,
        approvalId: String,
        error: SQLException,
    ): Nothing {
        if (error.sqlState != "23505") {
            throw error
        }
        val constraintName = extractConstraintName(error)
        if (constraintName != null) {
            require(!constraintName.contains("replay_envelope", ignoreCase = true)) {
                "suspended-invocation-replay-envelope-digest-already-exists"
            }
            throw IllegalArgumentException("suspended-invocation-already-exists")
        }
        require(!invocationExists(conn, approvalId)) {
            "suspended-invocation-already-exists"
        }
        throw IllegalArgumentException("suspended-invocation-replay-envelope-digest-already-exists")
    }

    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? =
        withSafeJdbc({ "Database operation failed for suspended invocation: $approvalId" }) {
            validateIdField(approvalId, "approvalId")

            val row = readCurrent(approvalId) ?: return@withSafeJdbc null
            SensitiveReplayEnvelope.of(row.messages)
        }

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? =
        withSafeJdbc({ "Database operation failed for suspended invocation: $approvalId" }) {
            validateIdField(approvalId, "approvalId")

            // Read + delete inside one explicit transaction with row lock
            dataSource.connection.use { conn ->
                val previousAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    val sql =
                        """
                        SELECT encrypted_replay_envelope, encryption_key_id, encryption_algorithm,
                               encryption_nonce, payload_digest, version
                        FROM suspended_invocations
                        WHERE invocation_id = ?
                        FOR UPDATE
                        """.trimIndent()
                    val row =
                        conn.prepareStatement(sql).use { stmt ->
                            stmt.setString(1, approvalId)
                            stmt.executeQuery().use { rs ->
                                if (!rs.next()) {
                                    conn.rollback()
                                    return@use null
                                }

                                val encrypted = readEncryptedFromRow(rs)
                                val v = rs.getLong("version")
                                val payload = decryptAndDeserialize(encrypted)
                                val domainMessages = payload.persistedMessages.map { toDomainMessage(it) }
                                Triple(encrypted, v, PayloadWithDomainMessages(payload.metadata, domainMessages))
                            }
                        } ?: return@use null
                    val (_, _, payloadWithMessages) = row

                    val deleteSql = "DELETE FROM suspended_invocations WHERE invocation_id = ?"
                    conn.prepareStatement(deleteSql).use { stmt ->
                        stmt.setString(1, approvalId)
                        stmt.executeUpdate()
                    }

                    conn.commit()
                    payloadWithMessages.metadata.toDomain()
                } catch (e: Exception) {
                    conn.rollback()
                    e.rethrowIfCancellation()
                    throw e
                } finally {
                    conn.autoCommit = previousAutoCommit
                }
            }
        }

    // ── Internal helpers ──────────────────────────────────────────

    /**
     * Validates the shared replay-envelope invariants between the
     * [SuspendedInvocationMetadata] and the replay envelope [Message]s that
     * will be persisted — mirroring the engine's [dev.tramai.engine.ReplayEnvelopeValidator]
     * (cross-module copy; the shared TCK pins the behavior so the copies
     * cannot drift):
     * - history-size consistency;
     * - the selected toolCallId is globally unique;
     * - the selected call sits at the metadata index/name in the latest
     *   assistant tool-call batch;
     * - the selected call's arguments are the redaction sentinel (exactly one
     *   sentinel, at the selected slot) — raw selected tool arguments never
     *   belong in a replay envelope.
     */
    private fun validateReplayEnvelopeInvariants(
        metadata: SuspendedInvocationMetadata,
        messages: List<Message>,
    ) {
        require(metadata.historySize >= 0) { "suspended-replay-envelope-history-size-negative" }
        require(messages.size > metadata.historySize) { "suspended-replay-envelope-history-size-mismatch" }

        val allSlots =
            messages.flatMapIndexed { messageIndex, message ->
                message.toolCalls.orEmpty().mapIndexed { toolCallIndex, call ->
                    ReplayToolCallSlot(messageIndex, toolCallIndex, call)
                }
            }
        val matchingSlots = allSlots.filter { it.call.id == metadata.toolCallId }
        require(matchingSlots.size == 1) { "suspended-replay-envelope-tool-call-id-mismatch" }
        val selectedSlot = matchingSlots.single()

        require(metadata.toolCallIndex >= 0) { "suspended-replay-envelope-tool-call-index-out-of-bounds" }
        require(selectedSlot.toolCallIndex == metadata.toolCallIndex) {
            "suspended-replay-envelope-tool-call-index-mismatch"
        }
        require(selectedSlot.call.name == metadata.toolName) {
            "suspended-replay-envelope-tool-call-name-mismatch"
        }

        val latestAssistantIdx =
            messages.indexOfLast {
                it.role == MessageRole.ASSISTANT && !it.toolCalls.isNullOrEmpty()
            }
        require(latestAssistantIdx >= 0) { "suspended-replay-envelope-assistant-batch-not-found" }
        require(selectedSlot.messageIndex == latestAssistantIdx) {
            "suspended-replay-envelope-tool-call-slot-mismatch"
        }

        val sentinelSlots =
            allSlots.filter {
                it.call.argumentsJson == REDACTED_APPROVAL_CONTINUATION_ARGUMENTS
            }
        require(sentinelSlots.size == 1) { "suspended-replay-envelope-redaction-count-mismatch" }
        val sentinelSlot = sentinelSlots.single()
        require(
            sentinelSlot.messageIndex == selectedSlot.messageIndex &&
                sentinelSlot.toolCallIndex == selectedSlot.toolCallIndex,
        ) {
            "suspended-replay-envelope-redaction-count-mismatch"
        }
    }

    private data class ReplayToolCallSlot(
        val messageIndex: Int,
        val toolCallIndex: Int,
        val call: ToolCall,
    )

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
    private fun invocationExists(
        conn: java.sql.Connection,
        approvalId: String,
    ): Boolean {
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
            val sql =
                """
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
                        governedRunIdentity = payload.governedRunIdentity?.toDomain(),
                    )
                }
            }
        }
    }

    private data class DecryptedRow(
        val metadata: PayloadMetadata,
        val messages: List<Message>,
        val version: Long,
        val governedRunIdentity: GovernedRunIdentity? = null,
    )

    private fun readEncryptedFromRow(rs: ResultSet): JdbcEncryptedReplayEnvelope {
        val ciphertext =
            rs.getBytes("encrypted_replay_envelope")
                ?: throw IllegalStateException("suspended-invocation-corrupted: encrypted_replay_envelope is null")
        val keyId =
            rs.getString("encryption_key_id")
                ?: throw IllegalStateException("suspended-invocation-corrupted: encryption_key_id is null")
        val algorithm =
            rs.getString("encryption_algorithm")
                ?: throw IllegalStateException("suspended-invocation-corrupted: encryption_algorithm is null")
        val nonce =
            rs.getBytes("encryption_nonce")
                ?: throw IllegalStateException("suspended-invocation-corrupted: encryption_nonce is null")
        val payloadDigest =
            rs.getString("payload_digest")
                ?: throw IllegalStateException("suspended-invocation-corrupted: payload_digest is null")

        return JdbcEncryptedReplayEnvelope(
            ciphertext = ciphertext,
            keyId = keyId,
            algorithm = algorithm,
            nonce = nonce,
            payloadDigest = payloadDigest,
        )
    }

    private fun decodePlaintext(encrypted: JdbcEncryptedReplayEnvelope): ByteArray =
        try {
            replayEnvelopeCodec.decode(encrypted)
        } catch (e: Exception) {
            throw IllegalStateException("suspended-invocation-decryption-failed", e)
        }

    private fun decryptAndDeserialize(encrypted: JdbcEncryptedReplayEnvelope): Payload {
        val plaintext = decodePlaintext(encrypted)

        val node =
            try {
                mapper.readTree(plaintext)
            } catch (e: Exception) {
                throw IllegalStateException("suspended-invocation-deserialization-failed", e)
            } ?: error("suspended-invocation-deserialization-failed")

        val payload =
            try {
                mapper.treeToValue(node, Payload::class.java)
            } catch (e: Exception) {
                throw IllegalStateException("suspended-invocation-deserialization-failed", e)
            } ?: error("suspended-invocation-deserialization-failed")

        validatePayloadVersion(node, payload)
        return payload
    }

    /**
     * V1 is legacy (no attribution persisted, and none allowed); V2 is governed and
     * mandatory; any other version is corruption. A malformed V2 never degrades to V1.
     */
    private fun validatePayloadVersion(
        node: JsonNode,
        payload: Payload,
    ) {
        // A payload written before governed attribution existed has no version field at all,
        // which is exactly the legacy case.
        val version = node.get("payloadVersion")?.asInt() ?: 1
        when (version) {
            1 -> {
                if (payload.governedRunIdentity != null) {
                    error("suspended-invocation-corrupted: legacy payload carries a governed identity")
                }
            }

            2 -> {
                val identity =
                    payload.governedRunIdentity
                        ?: error("suspended-invocation-corrupted: governed payload without a run identity")
                val components =
                    listOf(
                        identity.workloadId,
                        identity.configurationId,
                        identity.configurationVersion,
                        identity.environmentId,
                        identity.deploymentId,
                        identity.runId,
                    )
                if (components.any { it.isBlank() }) {
                    error("suspended-invocation-corrupted: governed run identity is incomplete")
                }
                // Bidirectional invariant, checked on decode as well as on create.
                if (identity.runId != payload.metadata.identityWorkflowRunId) {
                    error("suspended-invocation-corrupted: governed identity names another run")
                }
            }

            else -> {
                error("suspended-invocation-unsupported-payload-version: $version")
            }
        }
    }

    private fun validateIdField(
        value: String,
        fieldName: String,
    ) {
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

    private fun toDomainMessage(pm: PersistedMessage): Message =
        Message(
            role = MessageRole.valueOf(pm.role),
            content = pm.content,
            toolCalls = pm.toolCalls?.map { toDomainToolCall(it) },
        )

    private fun toDomainToolCall(ptc: PersistedToolCall): ToolCall =
        ToolCall(
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

    private fun toPersisted(msg: Message): PersistedMessage =
        PersistedMessage(
            role = msg.role.name,
            content = msg.content,
            toolCalls = msg.toolCalls?.map { toPersistedToolCall(it) },
        )

    private fun toPersistedToolCall(tc: ToolCall): PersistedToolCall =
        PersistedToolCall(
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
        val payloadVersion: Int = 1,
        val metadata: PayloadMetadata,
        val persistedMessages: List<PersistedMessage>,
        val governedRunIdentity: PayloadGovernedRunIdentity? = null,
    )

    /**
     * Persisted canonical run identity, carried INSIDE the encrypted payload (0.7.1d) so that
     * workload/deployment attribution is never promoted into plaintext query columns — the
     * same confidentiality decision the rest of this record already makes.
     *
     * `payloadVersion` is semantic: 1 means no governed attribution was persisted, 2 means it
     * was persisted and is mandatory. A v2 payload missing a component is corruption, never a
     * legacy fallback.
     */
    private data class PayloadGovernedRunIdentity(
        val workloadId: String,
        val configurationId: String,
        val configurationVersion: String,
        val environmentId: String,
        val deploymentId: String,
        val runId: String,
    ) {
        fun toDomain(): GovernedRunIdentity =
            GovernedRunIdentity(
                deployment =
                    WorkloadDeploymentIdentity(
                        workloadId = WorkloadId(workloadId),
                        configuration =
                            WorkloadConfigurationIdentity(
                                id = ConfigurationId(configurationId),
                                version = ConfigurationVersion(configurationVersion),
                            ),
                        environmentId = EnvironmentId(environmentId),
                        deploymentId = DeploymentId(deploymentId),
                    ),
                runId = RunId(runId),
            )

        companion object {
            fun fromDomain(identity: GovernedRunIdentity): PayloadGovernedRunIdentity =
                PayloadGovernedRunIdentity(
                    workloadId = identity.deployment.workloadId.value,
                    configurationId = identity.deployment.configuration.id.value,
                    configurationVersion = identity.deployment.configuration.version.value,
                    environmentId = identity.deployment.environmentId.value,
                    deploymentId = identity.deployment.deploymentId.value,
                    runId = identity.runId.value,
                )
        }
    }

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
            fun fromDomain(metadata: SuspendedInvocationMetadata): PayloadMetadata =
                PayloadMetadata(
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

        fun toDomain(): SuspendedInvocationMetadata =
            SuspendedInvocationMetadata(
                approvalId = approvalId,
                toolCallId = toolCallId,
                toolName = toolName,
                toolCallIndex = toolCallIndex,
                correlationId = correlationId,
                identity =
                    EngineExecutionIdentity(
                        workflowRunId = identityWorkflowRunId,
                        correlationId = identityCorrelationId,
                        workflowDigest = Sha256Digest.of(identityWorkflowDigest),
                        policyVersion = identityPolicyVersion,
                        actorId = identityActorId,
                    ),
                securityContext =
                    ExecutionSecurityContext(
                        dataClassification = securityDataClassification?.let { enumValueOf(it) },
                        classificationSource = securityClassificationSource?.let { enumValueOf(it) },
                    ),
                operationReference =
                    ResumeOperationReference(
                        serviceInterface = operationServiceInterface,
                        methodName = operationMethodName,
                        jvmMethodDescriptor = operationJvmMethodDescriptor,
                        resumeDefinitionDigest = Sha256Digest.of(operationResumeDefinitionDigest),
                    ),
                replayEnvelopeDigest = Sha256Digest.of(replayEnvelopeDigest),
                conversationId = conversationId,
                historySize = historySize,
                tokenBudgetSnapshot =
                    tokenBudgetTotalInputTokens?.let { inputTokens ->
                        TokenBudgetSnapshot(
                            totalInputTokens = inputTokens,
                            totalOutputTokens = tokenBudgetTotalOutputTokens ?: 0L,
                            totalInputCost = tokenBudgetTotalInputCost ?: 0.0,
                            totalOutputCost = tokenBudgetTotalOutputCost ?: 0.0,
                            warnIfExceeded = tokenBudgetWarnIfExceeded ?: false,
                        )
                    },
                toolReference =
                    ResumeToolReference(
                        toolName = toolReferenceName,
                        declarationDigest = Sha256Digest.of(toolReferenceDeclarationDigest),
                    ),
                toolSecurity = toolSecurity?.let { mapper.readValue(it) },
            )
    }
}
