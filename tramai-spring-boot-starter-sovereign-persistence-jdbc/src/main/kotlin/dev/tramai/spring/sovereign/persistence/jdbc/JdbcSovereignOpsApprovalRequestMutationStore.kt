package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.exception.GovernedRunContinuityException
import dev.tramai.core.identity.GovernedRunIdentity
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
import dev.tramai.engine.TokenBudgetSnapshot
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalRunAttribution
import dev.tramai.engine.approval.decodeApprovalAttribution
import dev.tramai.engine.approval.encodeApprovalAttribution
import dev.tramai.persistence.jdbc.JdbcContinuationArgumentsCodec
import dev.tramai.persistence.jdbc.JdbcEncryptedContinuationArguments
import dev.tramai.persistence.jdbc.JdbcEncryptedReplayEnvelope
import dev.tramai.persistence.jdbc.JdbcReplayEnvelopeCodec
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadata
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadataPolicy
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsApprovalRequestMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.crypto.SecretKey
import javax.sql.DataSource

class JdbcSovereignOpsApprovalRequestMutationStore(
    private val dataSource: DataSource,
    private val replayEnvelopeCodec: JdbcReplayEnvelopeCodec,
    private val continuationArgumentsCodec: JdbcContinuationArgumentsCodec,
    private val outboxPayloadCodec: JdbcOpsAuditOutboxPayloadCodec,
    private val encryptionKey: SecretKey,
    private val encryptionKeyId: String,
    private val clock: Clock = Clock.systemUTC(),
) : SovereignOpsApprovalRequestMutationStore {
    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    override suspend fun createApprovalRequest(
        request: ApprovalGatewayPersistenceRequest,
        auditIntent: SovereignOpsAuditOutboxRecord?,
        inboxMetadata: ApprovalInboxMetadata?,
        resumeCredential: ApprovalResumeCredentialRecord?,
    ): SovereignOpsApprovalRequestMutationResult =
        createApprovalRequestInternal(
            request = request,
            attribution = ApprovalRunAttribution.Ungoverned,
            auditIntent = auditIntent,
            inboxMetadata = inboxMetadata,
            resumeCredential = resumeCredential,
        )

    /**
     * Governed creation (0.7.1d1): the approval-row attribution and the suspension payload both come
     * from ONE canonical [GovernedRunIdentity], written inside the SAME transaction as the approval,
     * continuation, resume credential and inbox/audit artifacts.
     *
     * Takes the identity itself rather than a nullable value or a five-component snapshot, so the two
     * identity-bearing records cannot be built from independently interpreted input.
     *
     * This is a privileged persistence boundary: it re-checks that the request's binding names the
     * same run rather than trusting that the caller validated factory output.
     */
    internal suspend fun createGovernedApprovalRequest(
        request: ApprovalGatewayPersistenceRequest,
        identity: GovernedRunIdentity,
        auditIntent: SovereignOpsAuditOutboxRecord? = null,
        inboxMetadata: ApprovalInboxMetadata? = null,
        resumeCredential: ApprovalResumeCredentialRecord? = null,
    ): SovereignOpsApprovalRequestMutationResult {
        requireEveryCarrierNamesCanonicalRun(identity, request)
        return createApprovalRequestInternal(
            request = request,
            attribution = ApprovalRunAttribution.Governed(identity),
            auditIntent = auditIntent,
            inboxMetadata = inboxMetadata,
            resumeCredential = resumeCredential,
        )
    }

    /**
     * Every run-id carrier in the request must name the canonical run before this boundary writes
     * anything. The suspension record already carried `SuspendedInvocationMetadata.identity.workflowRunId`
     * before 0.7.1d; the governed payload adds `GovernedRunIdentity.runId`. Those two must never
     * disagree, or this path would persist a V2 suspension that the canonical JDBC reader rejects as
     * corruption. The factory is identity-untrusted input translation, so all carriers are checked
     * together rather than trusting that one correct binding implies the others.
     */
    private fun requireEveryCarrierNamesCanonicalRun(
        identity: GovernedRunIdentity,
        request: ApprovalGatewayPersistenceRequest,
    ) {
        val canonicalRunId = identity.runId.value
        val carriers =
            listOf(
                "approval binding" to request.approvalRequest.binding.workflowRunId,
                "continuation" to request.continuation.workflowRunId,
                "suspended invocation metadata" to
                    request.suspendedInvocationMetadata.identity.workflowRunId,
            )
        val mismatched = carriers.filter { (_, runId) -> runId != canonicalRunId }
        if (mismatched.isNotEmpty()) {
            throw GovernedRunContinuityException(
                "Governed approval creation for run '$canonicalRunId' was given " +
                    mismatched.joinToString { (carrier, runId) -> "$carrier naming run '$runId'" } +
                    ": every governed run-id carrier must name the same run",
            )
        }
    }

    /**
     * The ONE transaction body behind both entry points: one rollback path, one primary-key-conflict
     * path, one insert ordering. The entry points only distinguish attribution.
     *
     * [attribution] is resolved before the trust boundary by the gateway, so it is a value this layer
     * may interpret directly. [ApprovalRunAttribution.Ungoverned] keeps the released behaviour
     * byte-for-byte: no reserved keys are written and no attribution is decoded on the existing-row
     * paths.
     */
    private suspend fun createApprovalRequestInternal(
        request: ApprovalGatewayPersistenceRequest,
        attribution: ApprovalRunAttribution,
        auditIntent: SovereignOpsAuditOutboxRecord?,
        inboxMetadata: ApprovalInboxMetadata?,
        resumeCredential: ApprovalResumeCredentialRecord?,
    ): SovereignOpsApprovalRequestMutationResult {
        // The canonical identity, when governed: this SAME value feeds the approval-row attribution
        // and the suspended-invocation payload.
        val governedIdentity = (attribution as? ApprovalRunAttribution.Governed)?.identity
        validateRequest(request, auditIntent)
        inboxMetadata?.let(ApprovalInboxMetadataPolicy::validate)

        return dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val existing = selectApproval(conn, request.approvalRequest.approvalId)
                if (existing != null) {
                    conn.commit()
                    return@use SovereignOpsApprovalRequestMutationResult.Existing(
                        requireExistingIdentityMatches(existing, governedIdentity),
                    )
                }

                insertApproval(conn, request.approvalRequest, inboxMetadata, attribution)
                insertSuspendedInvocation(
                    conn = conn,
                    metadata = request.suspendedInvocationMetadata,
                    replayEnvelope = request.replayEnvelope,
                    governedIdentity = governedIdentity,
                )
                insertContinuation(
                    conn = conn,
                    continuation = request.continuation,
                    sensitiveArguments = request.sensitiveArguments,
                )

                if (resumeCredential != null) {
                    insertResumeCredential(conn, resumeCredential)
                }

                writeAuditOutboxArtifacts(conn, auditIntent)

                conn.commit()
                SovereignOpsApprovalRequestMutationResult.Created(
                    approvalId = request.approvalRequest.approvalId,
                    correlationId = request.suspendedInvocationMetadata.correlationId,
                    resumeToken = request.resumeToken,
                )
            } catch (e: SQLException) {
                conn.rollback()
                recoverExistingAfterPrimaryKeyRace(
                    error = e,
                    approvalId = request.approvalRequest.approvalId,
                    governedIdentity = governedIdentity,
                )?.let { return@use it }

                throw IllegalStateException(
                    "tramai-sovereign-ops-approval-request-mutation-database-failure",
                    e,
                )
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    /**
     * Writes the prepared outbox row and then its pending transition inside the caller's
     * transaction: like every other artifact of a creation, the two rows land together or not at
     * all.
     */
    private fun writeAuditOutboxArtifacts(
        conn: Connection,
        auditIntent: SovereignOpsAuditOutboxRecord?,
    ) {
        if (auditIntent == null) return
        val preparedPayload = mapper.writeValueAsBytes(auditIntent.toPersistedOutbox())
        val preparedEncrypted = outboxPayloadCodec.encode(preparedPayload)
        insertPreparedOutbox(conn, auditIntent, preparedEncrypted)

        val pendingAuditIntent =
            auditIntent.copy(
                status = SovereignOpsAuditOutboxStatus.PENDING,
            )
        val pendingPayload = mapper.writeValueAsBytes(pendingAuditIntent.toPersistedOutbox())
        val pendingEncrypted = outboxPayloadCodec.encode(pendingPayload)
        markPreparedOutboxPending(conn, pendingAuditIntent, pendingEncrypted)
    }


    /**
     * Recovery for the loser of a creation race: a primary-key violation means a concurrent writer
     * committed the row first, so the loser re-reads it on its own connection and applies the SAME
     * reconciliation as the initial SELECT — a concurrent run must never adopt the winner's identity.
     *
     * Returns null for anything else, so the caller keeps its database-failure wrapping, and lets
     * continuity/corruption exceptions from [requireExistingIdentityMatches] propagate unchanged.
     */
    private fun recoverExistingAfterPrimaryKeyRace(
        error: SQLException,
        approvalId: String,
        governedIdentity: GovernedRunIdentity?,
    ): SovereignOpsApprovalRequestMutationResult.Existing? {
        if (!isApprovalPrimaryKeyViolation(error)) return null

        val raced = selectApprovalAfterRollback(approvalId)
        return raced?.let {
            SovereignOpsApprovalRequestMutationResult.Existing(
                requireExistingIdentityMatches(it, governedIdentity),
            )
        }
    }


    private fun validateRequest(
        request: ApprovalGatewayPersistenceRequest,
        auditIntent: SovereignOpsAuditOutboxRecord?,
    ) {
        val approval = request.approvalRequest
        val continuation = request.continuation
        val suspended = request.suspendedInvocationMetadata
        validateIdField(approval.approvalId, "approvalId")
        validateIdField(approval.requestedBy, "requestedBy")
        SafeActorIdPolicy.validateActorId(approval.requestedBy, "requestedBy")
        require(approval.status == ApprovalStatus.PENDING) {
            "tramai-sovereign-ops-approval-request-invalid-status"
        }
        require(approval.version == 0L) {
            "tramai-sovereign-ops-approval-request-invalid-version"
        }
        require(approval.decidedBy == null && approval.decidedAt == null && approval.decisionComment == null) {
            "tramai-sovereign-ops-approval-request-must-not-be-decided"
        }
        require(approval.consumedBy == null && approval.consumedAt == null) {
            "tramai-sovereign-ops-approval-request-must-not-be-consumed"
        }

        val now = clock.instant()
        require(approval.requestedAt <= now) {
            "tramai-sovereign-ops-approval-request-requested-at-in-future"
        }
        require(approval.expiresAt > now) {
            "tramai-sovereign-ops-approval-request-expired-at-creation"
        }
        require(approval.expiresAt > approval.requestedAt) {
            "tramai-sovereign-ops-approval-request-invalid-expiry"
        }
        require(Duration.between(approval.requestedAt, approval.expiresAt) <= Duration.ofMinutes(15)) {
            "tramai-sovereign-ops-approval-request-exceeds-max-ttl"
        }

        require(!continuation.createdAt.isAfter(now)) {
            "tramai-sovereign-ops-approval-request-continuation-created-at-in-future"
        }
        require(continuation.approvalExpiresAt > now) {
            "tramai-sovereign-ops-approval-request-continuation-expired-at-creation"
        }

        // Replay-envelope digest verification: recompute canonical digest from actual
        // replay-envelope messages and verify it matches the metadata digest.
        // This mirrors JdbcSuspendedInvocationStore.validateReplayEnvelopeDigest.
        val replayMessages = request.replayEnvelope.revealForResume().messages
        val canonicalDigest =
            ReplayEnvelopeDigestHelper.compute(
                request.suspendedInvocationMetadata.operationReference,
                replayMessages,
            )
        require(canonicalDigest == request.suspendedInvocationMetadata.replayEnvelopeDigest) {
            "tramai-sovereign-ops-replay-envelope-digest-mismatch: " +
                "canonical=$canonicalDigest, provided=${request.suspendedInvocationMetadata.replayEnvelopeDigest}"
        }

        require(continuation.approvalId == approval.approvalId) {
            "tramai-sovereign-ops-approval-request-continuation-id-mismatch"
        }
        require(continuation.status == ApprovalContinuationStatus.PENDING) {
            "tramai-sovereign-ops-approval-request-invalid-continuation-status"
        }
        require(continuation.version == 0L) {
            "tramai-sovereign-ops-approval-request-invalid-continuation-version"
        }
        // Mirrors JdbcApprovalContinuationStore.create validation
        require(continuation.claimedBy == null && continuation.claimedAt == null) {
            "tramai-sovereign-ops-approval-request-continuation-must-not-be-claimed"
        }
        require(continuation.completedAt == null) {
            "tramai-sovereign-ops-approval-request-continuation-must-not-be-completed"
        }
        validateIdField(continuation.workflowRunId, "continuation.workflowRunId")
        validateIdField(continuation.correlationId, "continuation.correlationId")
        validateIdField(continuation.toolCallId, "continuation.toolCallId")
        validateIdField(continuation.toolName, "continuation.toolName")
        validateDigestField(continuation.argumentsDigest.value)
        require(!continuation.policyVersion.isNullOrBlank()) {
            "tramai-sovereign-ops-approval-request-continuation-missing-policy-version"
        }
        validateDigestField(continuation.workflowDigest.value)
        require(continuation.approvalExpiresAt > continuation.createdAt) {
            "tramai-sovereign-ops-approval-request-continuation-invalid-expiry"
        }
        require(Duration.between(continuation.createdAt, continuation.approvalExpiresAt) <= Duration.ofMinutes(15)) {
            "tramai-sovereign-ops-approval-request-continuation-exceeds-max-ttl"
        }
        require(suspended.approvalId == approval.approvalId) {
            "tramai-sovereign-ops-approval-request-suspended-id-mismatch"
        }

        auditIntent?.let {
            require(it.status == SovereignOpsAuditOutboxStatus.PREPARED) {
                "tramai-sovereign-ops-outbox-invalid-status"
            }
        }
    }

    private fun insertApproval(
        conn: Connection,
        request: ApprovalRequest,
        inboxMetadata: ApprovalInboxMetadata?,
        attribution: ApprovalRunAttribution,
    ) {
        val now = clock.instant()
        val nowOdt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        val inbox =
            inboxMetadata?.let { meta ->
                InboxMetadata(
                    requiredRole = meta.requiredRole?.value,
                    riskLevel = meta.riskLevel,
                    subjectType = meta.subjectType,
                    subjectId = meta.subjectId,
                    recommendationType = meta.recommendationType,
                )
            }
        val reservedAttribution =
            (attribution as? ApprovalRunAttribution.Governed)?.let { encodeApprovalAttribution(it.identity) }
        val metadata =
            ApprovalMetadata(
                binding =
                    BindingMetadata(
                        workflowRunId = request.binding.workflowRunId,
                        toolName = request.binding.toolName,
                        argumentsDigest = request.binding.argumentsDigest.value,
                        policyVersion = request.binding.policyVersion,
                        workflowDigest = request.binding.workflowDigest.value,
                        approvalTokenDigest = request.binding.approvalTokenDigest.value,
                    ),
                requestedBy = request.requestedBy,
                expiresAt = request.expiresAt.toString(),
                requestedAt = request.requestedAt.toString(),
                decidedBy = null,
                decisionComment = null,
                consumedBy = null,
                consumedAt = null,
                inbox = inbox,
                attribution = reservedAttribution,
            )
        val sql =
            """
            INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
            VALUES (?, 'PENDING', ?, ?::jsonb, 0)
            """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, request.approvalId)
            stmt.setObject(2, nowOdt)
            stmt.setString(3, mapper.writeValueAsString(metadata))
            stmt.executeUpdate()
        }
    }

    private fun insertSuspendedInvocation(
        conn: Connection,
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
        governedIdentity: GovernedRunIdentity?,
    ) {
        val payload =
            SuspendedPayload(
                metadata = SuspendedPayloadMetadata.fromDomain(metadata, mapper),
                payloadVersion = if (governedIdentity == null) null else GOVERNED_PAYLOAD_VERSION,
                governedRunIdentity = governedIdentity?.let { PayloadGovernedRunIdentity.fromDomain(it) },
                persistedMessages = replayEnvelope.revealForResume().messages.map { it.toPersisted() },
            )
        val encrypted = replayEnvelopeCodec.encode(mapper.writeValueAsBytes(payload))
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
            stmt.setObject(11, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            stmt.executeUpdate()
        }
    }

    private fun insertContinuation(
        conn: Connection,
        continuation: ApprovalContinuation,
        sensitiveArguments: SensitiveToolArguments,
    ) {
        val encrypted =
            continuationArgumentsCodec.encode(
                sensitiveArguments.reveal().toByteArray(Charsets.UTF_8),
            )
        val sql =
            """
            INSERT INTO approval_continuations (
                approval_id, status, version, created_at, approval_expires_at,
                workflow_run_id, correlation_id, tool_call_id, tool_name,
                arguments_digest, policy_version, workflow_digest,
                encrypted_arguments, encryption_key_id, encryption_algorithm,
                encryption_nonce, payload_digest
            ) VALUES (
                ?, 'PENDING', 0, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?,
                ?, ?
            )
            """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, continuation.approvalId)
            stmt.setObject(2, OffsetDateTime.ofInstant(continuation.createdAt, ZoneOffset.UTC))
            stmt.setObject(3, OffsetDateTime.ofInstant(continuation.approvalExpiresAt, ZoneOffset.UTC))
            stmt.setString(4, continuation.workflowRunId)
            stmt.setString(5, continuation.correlationId)
            stmt.setString(6, continuation.toolCallId)
            stmt.setString(7, continuation.toolName)
            stmt.setString(8, continuation.argumentsDigest.value)
            stmt.setString(9, continuation.policyVersion)
            stmt.setString(10, continuation.workflowDigest.value)
            bindEncryptedArguments(stmt, encrypted)
            stmt.executeUpdate()
        }
    }

    private fun bindEncryptedArguments(
        stmt: java.sql.PreparedStatement,
        encrypted: JdbcEncryptedContinuationArguments,
    ) {
        stmt.setBytes(11, encrypted.ciphertext)
        stmt.setString(12, encrypted.keyId)
        stmt.setString(13, encrypted.algorithm)
        stmt.setBytes(14, encrypted.nonce)
        stmt.setString(15, encrypted.payloadDigest)
    }

    /**
     * Reconciles an existing approval row with the creating request's canonical identity.
     *
     * The legacy path returns the row exactly as before: no attribution is decoded, so an ungoverned
     * caller keeps the released idempotency semantics even if a metadata block were malformed.
     *
     * The governed path treats durable attribution as authoritative: only the canonical identity may
     * be adopted, an un-attributed or foreign row aborts [GovernedRunContinuityException], and
     * partial/malformed attribution propagates as corruption instead of being reclassified.
     */
    private fun requireExistingIdentityMatches(
        selected: SelectedApproval,
        governedIdentity: GovernedRunIdentity?,
    ): ApprovalRequest {
        if (governedIdentity == null) return selected.approval
        val persisted =
            decodeApprovalAttribution(
                workflowRunId = selected.approval.binding.workflowRunId,
                metadata = selected.rawAttribution ?: emptyMap(),
            )
        if (persisted == ApprovalRunAttribution.Governed(governedIdentity)) return selected.approval
        val attributed = persisted as? ApprovalRunAttribution.Governed
        val reason =
            if (attributed != null) {
                "is attributed to run '${attributed.identity.runId.value}'"
            } else {
                "carries no governed attribution"
            }
        throw GovernedRunContinuityException(
            "Existing approval '${selected.approval.approvalId}' $reason, so run " +
                "'${governedIdentity.runId.value}' cannot adopt it",
        )
    }

    private fun selectApproval(
        conn: Connection,
        approvalId: String,
    ): SelectedApproval? {
        val sql =
            """
            SELECT approval_id, status, created_at, decided_at, decision_actor_hash, decision_type,
                   sanitized_metadata, version
            FROM approvals
            WHERE approval_id = ?
            """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, approvalId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapSelectedApproval(rs) else null
            }
        }
    }

    private fun selectApprovalAfterRollback(approvalId: String): SelectedApproval? =
        dataSource.connection.use { conn -> selectApproval(conn, approvalId) }

    private fun mapSelectedApproval(rs: ResultSet): SelectedApproval {
        val metadata = mapper.readValue<ApprovalMetadata>(rs.getString("sanitized_metadata"))
        return SelectedApproval(
            approval = mapApprovalRequest(rs, metadata),
            rawAttribution = metadata.attribution,
        )
    }

    /**
     * Maps the row's columns to [ApprovalRequest]. [metadata] is the already-parsed
     * `sanitized_metadata` column, so a row is decoded exactly once.
     */
    private fun mapApprovalRequest(
        rs: ResultSet,
        metadata: ApprovalMetadata,
    ): ApprovalRequest =
        ApprovalRequest(
            approvalId = rs.getString("approval_id"),
            binding =
                ApprovalBinding(
                    workflowRunId = metadata.binding.workflowRunId,
                    toolName = metadata.binding.toolName,
                    argumentsDigest = Sha256Digest.of(metadata.binding.argumentsDigest),
                    policyVersion = metadata.binding.policyVersion,
                    workflowDigest = Sha256Digest.of(metadata.binding.workflowDigest),
                    approvalTokenDigest = Sha256Digest.of(metadata.binding.approvalTokenDigest),
                ),
            status = ApprovalStatus.valueOf(rs.getString("status")),
            requestedBy = metadata.requestedBy,
            requestedAt = Instant.parse(metadata.requestedAt),
            expiresAt = Instant.parse(metadata.expiresAt),
            decidedBy = metadata.decidedBy,
            decidedAt = rs.getObject("decided_at", OffsetDateTime::class.java)?.toInstant(),
            decisionComment = metadata.decisionComment,
            consumedBy = metadata.consumedBy,
            consumedAt = metadata.consumedAt?.let(Instant::parse),
            version = rs.getLong("version"),
        )

    private fun insertPreparedOutbox(
        conn: Connection,
        record: SovereignOpsAuditOutboxRecord,
        encrypted: JdbcEncryptedAuditOutboxPayload,
    ) {
        val sql =
            """
            INSERT INTO audit_outbox (
                outbox_id, event_key, status, correlation_key_hash,
                created_at, attempt_count,
                encrypted_payload, encryption_key_id, encryption_algorithm,
                encryption_nonce, payload_digest, version
            ) VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 1)
            """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, record.outboxId)
            stmt.setString(2, record.eventKey)
            stmt.setString(3, record.status.name)
            stmt.setString(4, record.aggregateIdDigest)
            stmt.setTimestamp(5, java.sql.Timestamp.from(record.createdAt))
            stmt.setBytes(6, encrypted.ciphertext)
            stmt.setString(7, encrypted.keyId)
            stmt.setString(8, encrypted.algorithm)
            stmt.setBytes(9, encrypted.nonce)
            stmt.setString(10, encrypted.payloadDigest)
            stmt.executeUpdate()
        }
    }

    private fun markPreparedOutboxPending(
        conn: Connection,
        record: SovereignOpsAuditOutboxRecord,
        encrypted: JdbcEncryptedAuditOutboxPayload,
    ) {
        val sql =
            """
            UPDATE audit_outbox
            SET status = 'PENDING',
                encrypted_payload = ?,
                encryption_key_id = ?,
                encryption_algorithm = ?,
                encryption_nonce = ?,
                payload_digest = ?,
                version = version + 1
            WHERE outbox_id = ? AND status = 'PREPARED'
            """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setBytes(1, encrypted.ciphertext)
            stmt.setString(2, encrypted.keyId)
            stmt.setString(3, encrypted.algorithm)
            stmt.setBytes(4, encrypted.nonce)
            stmt.setString(5, encrypted.payloadDigest)
            stmt.setString(6, record.outboxId)
            check(stmt.executeUpdate() == 1) {
                "tramai-sovereign-ops-outbox-concurrent-update"
            }
        }
    }

    private fun insertResumeCredential(
        conn: Connection,
        credential: ApprovalResumeCredentialRecord,
    ) {
        val plaintext =
            credential.resumeToken
                .revealForInternalResume()
                .value
                .encodeToByteArray()
        val encrypted = DefaultJdbcPayloadCrypto.encrypt(plaintext, encryptionKey, encryptionKeyId)
        val sql =
            """
            INSERT INTO tramai_approval_resume_credentials
                (approval_id, workflow_run_id, encrypted_resume_token,
                 encryption_key_id, encryption_algorithm, encryption_nonce,
                 payload_digest, created_at, expires_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, credential.approvalId.value)
            stmt.setString(2, credential.workflowRunId.value)
            stmt.setBytes(3, encrypted.ciphertext)
            stmt.setString(4, encrypted.keyId)
            stmt.setString(5, encrypted.algorithm)
            stmt.setBytes(6, encrypted.nonce)
            stmt.setString(7, encrypted.payloadDigest)
            stmt.setTimestamp(8, java.sql.Timestamp.from(credential.createdAt))
            stmt.setTimestamp(9, java.sql.Timestamp.from(credential.expiresAt))
            stmt.setLong(10, credential.version)
            stmt.executeUpdate()
        }
    }

    private fun isApprovalPrimaryKeyViolation(error: SQLException): Boolean =
        error.sqlState == "23505" &&
            (
                error.message?.contains("approvals_pkey", ignoreCase = true) == true ||
                    error.message?.contains("approval_id", ignoreCase = true) == true
            )

    private fun validateIdField(
        value: String,
        fieldName: String,
    ) {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
        require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
        require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(trimmed.length <= 256) { "$fieldName exceeds maximum length of 256" }
    }

    private fun validateDigestField(value: String) {
        require(value.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
            "tramai-sovereign-ops-digest-field-must-be-sha256"
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BindingMetadata(
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: String,
    val policyVersion: String,
    val workflowDigest: String,
    val approvalTokenDigest: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class InboxMetadata(
    val requiredRole: String? = null,
    val riskLevel: String? = null,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val recommendationType: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ApprovalMetadata(
    val binding: BindingMetadata,
    val requestedBy: String,
    val expiresAt: String,
    val requestedAt: String,
    val decidedBy: String?,
    val decisionComment: String?,
    val consumedBy: String?,
    val consumedAt: String?,
    val inbox: InboxMetadata? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val attribution: Map<String, String>? = null,
)

private data class PersistedMessage(
    val role: String,
    val content: String,
    val toolCalls: List<PersistedToolCall>?,
)

private data class PersistedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

private fun Message.toPersisted(): PersistedMessage =
    PersistedMessage(
        role = role.name,
        content = content,
        toolCalls =
            toolCalls?.map {
                PersistedToolCall(
                    id = it.id,
                    name = it.name,
                    argumentsJson = it.argumentsJson,
                )
            },
    )

/**
 * Combined payload that is serialised as JSON, then encrypted.
 *
 * The governed members sit at the ROOT, mirroring the canonical suspension store's `Payload`: its
 * reader expects `payloadVersion` and `governedRunIdentity` as siblings of `metadata`, not inside it,
 * and reflects the two types strictly enough that a nested shape fails to deserialize.
 *
 * Both are omitted when null, so an ungoverned payload keeps the released V1 byte shape (the canonical
 * store's own `payloadVersion` is a non-null defaulting Int, which is why it always emits one and this
 * payload deliberately does not).
 */
private data class SuspendedPayload(
    val metadata: SuspendedPayloadMetadata,
    val persistedMessages: List<PersistedMessage>,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val payloadVersion: Int? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val governedRunIdentity: PayloadGovernedRunIdentity? = null,
)

/** Carries the raw attribution read from a row alongside the mapped request. */
private data class SelectedApproval(
    val approval: ApprovalRequest,
    val rawAttribution: Map<String, String>?,
)

/** Semantic payload version marking a suspension that MUST carry a governed identity. */
private const val GOVERNED_PAYLOAD_VERSION: Int = 2

/**
 * Persisted canonical run identity (0.7.1d1), carried INSIDE the encrypted suspension payload so
 * workload/configuration/environment/deployment attribution is never promoted into a plaintext query
 * column — the same confidentiality decision the standalone JDBC suspension store makes.
 *
 * Deliberately replicated rather than widening that store's private API: the payload shape is proven
 * equivalent by test instead of being shared through a new public surface.
 */
private data class PayloadGovernedRunIdentity(
    val workloadId: String,
    val configurationId: String,
    val configurationVersion: String,
    val environmentId: String,
    val deploymentId: String,
    val runId: String,
) {
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

private data class SuspendedPayloadMetadata(
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
        fun fromDomain(
            metadata: SuspendedInvocationMetadata,
            mapper: ObjectMapper,
        ): SuspendedPayloadMetadata =
            SuspendedPayloadMetadata(
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
}
