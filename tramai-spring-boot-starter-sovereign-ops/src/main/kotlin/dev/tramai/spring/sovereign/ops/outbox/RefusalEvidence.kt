package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.operator.ActorIdentityDigest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The privileged transition a refusal was about. Part of the canonical key, so an approve refusal and a
 * deny refusal of the same target can never collapse into one evidence record.
 */
enum class RefusalOperation(
    val wireName: String,
) {
    APPROVE("approval-approve"),
    DENY("approval-deny"),
}

/**
 * 0.7.6f §5.2: the durable identity of one authorization refusal.
 *
 * Everything that makes two refusals *different evidence* is here, and nothing that describes delivery is:
 * no timestamp, no correlation, no attempt count. Two identical refused requests therefore produce the same
 * [eventKey] and dedupe onto one record, while any evidence-bearing difference produces a different key.
 *
 * @param approvalId the caller-supplied approval id. Only its digest is stored, so refusal evidence never
 * holds a raw approval id.
 * @param issuer verified issuer of the acting operator; paired with [subject] for the identity digest.
 * @param refusalReason the closed refusal reason's wire name, e.g. `missing-capability`.
 * @param policyVersion the approval policy version consulted while deciding, or `null` when none was. The
 * absence is encoded explicitly rather than guessed, because `capabilityRefusal` runs before any lookup.
 */
data class RefusalEvidence(
    val operation: RefusalOperation,
    val approvalId: String,
    val issuer: String,
    val subject: String,
    val refusalReason: String,
    val policyVersion: String?,
    val expectedVersion: Long,
    val correlationId: String? = null,
    val workflowRunId: String? = null,
) {
    /** Digest of the caller-supplied approval id; never the raw id. */
    val approvalIdDigest: String get() = "sha256:" + sha256Hex(approvalId)

    /** Canonical durable actor identity for the acting operator. */
    val actorIdentityDigest: String get() = ActorIdentityDigest.of(issuer, subject)

    /**
     * The stored, indexable event key: `refusal-sha256:` plus 64 lowercase hex characters.
     *
     * PostgreSQL `text` cannot hold NUL bytes, so the canonical bytes below are only ever the hash input;
     * the stored form is printable, bounded and unique-indexable.
     */
    val eventKey: String get() = EVENT_KEY_PREFIX + sha256Hex(canonicalBytes())

    /**
     * Versioned, domain-separated, length-prefixed canonical encoding.
     *
     * Length prefixes rather than delimiters: a reason or version string containing a separator cannot be
     * re-split into a different refusal. The version integer sits inside the input so a future encoding
     * cannot collide with this one.
     */
    private fun canonicalBytes(): ByteArray =
        ByteArrayOutputStream()
            .also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.write(DOMAIN.toByteArray(StandardCharsets.UTF_8))
                    out.writeByte(0)
                    out.writeInt(ENCODING_VERSION)
                    out.writeLengthPrefixed(operation.wireName)
                    out.writeLengthPrefixed(approvalIdDigest)
                    out.writeLengthPrefixed(actorIdentityDigest)
                    out.writeLengthPrefixed(refusalReason)
                    writePolicyField(out)
                    out.writeLengthPrefixed(expectedVersion.toString())
                }
            }.toByteArray()

    /**
     * Policy consulted or not, always in one byte. `0x00` means no policy version existed and none was
     * consulted; `0x01` means one was, and its value follows. "Not consulted" and "consulted and empty"
     * are therefore distinct inputs and cannot produce the same key.
     */
    private fun writePolicyField(out: DataOutputStream) {
        if (policyVersion == null) {
            out.writeByte(0x00)
        } else {
            out.writeByte(0x01)
            out.writeLengthPrefixed(policyVersion)
        }
    }

    init {
        require(approvalId.isNotBlank()) { "tramai-refusal-evidence-approval-id-blank" }
        require(refusalReason.isNotBlank()) { "tramai-refusal-evidence-reason-blank" }
        require(policyVersion == null || policyVersion.isNotBlank()) {
            "tramai-refusal-evidence-policy-version-blank"
        }
    }

    /**
     * The outbox record for this refusal: `PENDING` and dispatchable at commit, because a refusal has no
     * business transition to wait for.
     *
     * `approvalStatus`/`approvalVersion` are null by design -- a refusal decided nothing, so it must not
     * claim a status or a new version.
     *
     * `previousApprovalStatus`/`previousApprovalVersion` are null too, deliberately: a capability refusal
     * never read the approval, so naming a status it never saw would be a false claim, and the codec accepts
     * previous state only as a complete pair.
     *
     * The key inputs are nonetheless persisted as their own refusal fields
     * ([SovereignOpsAuditOutboxRecord.refusalPolicyVersion],
     * [SovereignOpsAuditOutboxRecord.refusalExpectedVersion]). A hash proves two refusals are the same; it
     * cannot tell an auditor which policy governed one of them. `refusalPolicyVersion = null` therefore
     * means "no policy consulted", which is a fact, not a missing value.
     */
    fun toOutboxRecord(
        createdAt: Instant,
        outboxId: String = UUID.randomUUID().toString(),
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateType = AGGREGATE_TYPE,
            aggregateIdDigest = approvalIdDigest,
            operation = operation.wireName,
            eventKey = eventKey,
            actor = subject,
            workflowRunId = workflowRunId,
            correlationId = correlationId,
            approvalStatus = null,
            approvalVersion = null,
            reasonDigest = "sha256:" + sha256Hex(refusalReason),
            reasonLength = refusalReason.length,
            createdAt = createdAt,
            status = SovereignOpsAuditOutboxStatus.PENDING,
            actorIssuer = issuer,
            actorIdentityDigest = actorIdentityDigest,
            previousApprovalStatus = null,
            previousApprovalVersion = null,
            evidenceKind = SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL,
            refusalReason = refusalReason,
            refusalPolicyVersion = policyVersion,
            refusalExpectedVersion = expectedVersion,
        )

    /**
     * Whether a stored record describes the same refusal as this evidence, judged only on immutable logical
     * facts. Delivery metadata (timestamps, status, attempt count, claim owner, error codes) is excluded:
     * it legitimately differs between the stored record and a retry, and comparing it would turn a correct
     * dedupe into a false integrity error.
     *
     * The actor text is compared, not only its digest: two rows can share a digest while one names a
     * different subject, and returning that row as "the same refusal" would attach the wrong name to the
     * evidence. The digest alone cannot catch a digest that was computed from the right input but stored
     * beside the wrong label.
     *
     * Both stored refusal fields are compared as well: they are the human-readable form of two key inputs,
     * so a row that disagrees with either is a different refusal carrying someone else's key.
     */
    fun matches(record: SovereignOpsAuditOutboxRecord): Boolean =
        record.evidenceKind == SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL &&
            record.operation == operation.wireName &&
            record.aggregateIdDigest == approvalIdDigest &&
            record.actorIdentityDigest == actorIdentityDigest &&
            record.actorIssuer == issuer &&
            record.actor == subject &&
            record.refusalReason == refusalReason &&
            record.refusalPolicyVersion == policyVersion &&
            record.refusalExpectedVersion == expectedVersion &&
            record.approvalStatus == null &&
            record.approvalVersion == null

    companion object {
        private const val AGGREGATE_TYPE = "approval"
        private const val DOMAIN = "tramai/refusal-evidence"
        private const val ENCODING_VERSION = 1
        private const val EVENT_KEY_PREFIX = "refusal-sha256:"

        private fun DataOutputStream.writeLengthPrefixed(value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            writeInt(encoded.size)
            write(encoded)
        }
    }
}

private fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(StandardCharsets.UTF_8))

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
