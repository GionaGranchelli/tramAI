package dev.tramai.core.approval.gateway

/**
 * SPI for storing and retrieving sealed resume credentials.
 *
 * Credentials are created atomically during transactional approval-request
 * creation and are consumed exclusively by the internal runtime-owned resume
 * path. They must never be exposed through inbox, REST, audit, logs, or UI.
 *
 * Implementations MUST encrypt the [SealedResumeToken] at rest and MUST NOT
 * log or expose the raw token value.
 */
interface ApprovalResumeCredentialStore {

    /**
     * Persist a sealed resume credential.
     *
     * @throws IllegalStateException if a credential for [record.approvalId] already exists.
     */
    suspend fun create(record: ApprovalResumeCredentialRecord)

    /**
     * Retrieve a sealed resume credential by approval ID.
     * Returns null if no credential exists for the given approval.
     */
    suspend fun get(approvalId: ApprovalId): ApprovalResumeCredentialRecord?

    /**
     * Delete a sealed resume credential by approval ID.
     * Used after successful resume to purge the credential.
     * Idempotent — succeeds if no credential exists.
     */
    suspend fun delete(approvalId: ApprovalId)
}
