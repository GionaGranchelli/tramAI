package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialStore
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.SealedResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.crypto.SecretKey

/**
 * JDBC-backed [ApprovalResumeCredentialStore] that encrypts resume tokens
 * at rest using [DefaultJdbcPayloadCrypto].
 *
 * The credential is stored in the `tramai_approval_resume_credentials` table
 * and encrypted with AES-256-GCM. The caller provides the encryption [key]
 * and a [keyId] that identifies the key in the `encryption_key_id` column.
 *
 * @param dataSourceProvider A function that provides a JDBC [Connection].
 *        The connection is expected to already have autoCommit disabled when
 *        used within a multi-record transaction.
 * @param key The AES-256 encryption key for at-rest encryption.
 * @param keyId Identifier stored in `encryption_key_id` (default: "default").
 */
class JdbcApprovalResumeCredentialStore(
    private val dataSourceProvider: () -> Connection,
    private val key: SecretKey,
    private val keyId: String = "default",
) : ApprovalResumeCredentialStore {

    override suspend fun create(record: ApprovalResumeCredentialRecord) {
        val plaintext = record.resumeToken.revealForInternalResume().value.encodeToByteArray()
        val encrypted = DefaultJdbcPayloadCrypto.encrypt(plaintext, key, keyId)
        val conn = dataSourceProvider()
        val sql = """
            INSERT INTO tramai_approval_resume_credentials
                (approval_id, workflow_run_id, encrypted_resume_token,
                 encryption_key_id, encryption_algorithm, encryption_nonce,
                 payload_digest, created_at, expires_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        try {
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, record.approvalId.value)
                stmt.setString(2, record.workflowRunId.value)
                stmt.setBytes(3, encrypted.ciphertext)
                stmt.setString(4, encrypted.keyId)
                stmt.setString(5, encrypted.algorithm)
                stmt.setBytes(6, encrypted.nonce)
                stmt.setString(7, encrypted.payloadDigest)
                stmt.setObject(8, record.createdAt.atOffset(ZoneOffset.UTC))
                stmt.setObject(9, record.expiresAt.atOffset(ZoneOffset.UTC))
                stmt.setLong(10, record.version)
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            if (e.sqlState == "23505") {
                throw IllegalStateException(
                    "tramai-sovereign-credential-already-exists",
                    e,
                )
            }
            throw IllegalStateException(
                "tramai-sovereign-credential-store-failed",
                e,
            )
        }
    }

    override suspend fun get(approvalId: ApprovalId): ApprovalResumeCredentialRecord? {
        val conn = dataSourceProvider()
        val sql = """
            SELECT approval_id, workflow_run_id, encrypted_resume_token,
                   encryption_key_id, encryption_algorithm, encryption_nonce,
                   payload_digest, created_at, expires_at, version
            FROM tramai_approval_resume_credentials
            WHERE approval_id = ?
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, approvalId.value)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                val ciphertext = rs.getBytes("encrypted_resume_token")
                val encryptedPayload = EncryptedPayload(
                    ciphertext = ciphertext,
                    keyId = rs.getString("encryption_key_id"),
                    algorithm = rs.getString("encryption_algorithm"),
                    nonce = rs.getBytes("encryption_nonce"),
                    payloadDigest = rs.getString("payload_digest"),
                )
                DefaultJdbcPayloadCrypto.verifyDigest(encryptedPayload)
                val plaintext = DefaultJdbcPayloadCrypto.decrypt(encryptedPayload, key)
                val tokenValue = plaintext.decodeToString()
                return ApprovalResumeCredentialRecord(
                    approvalId = ApprovalId(rs.getString("approval_id")),
                    workflowRunId = WorkflowRunId(rs.getString("workflow_run_id")),
                    resumeToken = SealedResumeToken.seal(ResumeToken(tokenValue)),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                    version = rs.getLong("version"),
                )
            }
        }
    }

    override suspend fun delete(approvalId: ApprovalId) {
        val conn = dataSourceProvider()
        val sql = "DELETE FROM tramai_approval_resume_credentials WHERE approval_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, approvalId.value)
            stmt.executeUpdate()
        }
    }
}
