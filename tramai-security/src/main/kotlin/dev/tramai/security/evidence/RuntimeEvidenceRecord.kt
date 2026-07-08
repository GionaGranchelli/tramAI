package dev.tramai.security.evidence

import java.security.MessageDigest
import java.time.Instant

/**
 * Runtime evidence record following the runtime-evidence.v1 schema
 * defined in docs/evidence/runtime-evidence-export-model.md.
 *
 * Each record represents a single runtime decision (policy, approval,
 * or provider routing) that can be exported into a reviewable,
 * digest-verifiable evidence artifact.
 */
data class RuntimeEvidenceRecord(
    val schemaVersion: String = "runtime-evidence.v1",
    val eventId: String,
    val eventType: String,
    val workflowRunId: String?,
    val correlationId: String?,
    val actor: String?,
    val createdAt: Instant,
    val source: RuntimeEvidenceSource,
    val decision: RuntimeEvidenceDecision,
    val digests: RuntimeEvidenceDigests,
    val metadata: Map<String, String> = emptyMap(),
)

data class RuntimeEvidenceSource(
    val component: String,
    val module: String? = null,
)

data class RuntimeEvidenceDecision(
    val kind: String,
    val reasonCode: String?,
)

data class RuntimeEvidenceDigests(
    val subjectDigest: String,
    val payloadDigest: String,
) {
    init {
        require(DIGEST_REGEX.matches(subjectDigest)) {
            "subjectDigest must match $DIGEST_REGEX: $subjectDigest"
        }
        require(DIGEST_REGEX.matches(payloadDigest)) {
            "payloadDigest must match $DIGEST_REGEX: $payloadDigest"
        }
    }

    companion object {
        private val DIGEST_REGEX = Regex("^sha256:[0-9a-f]{64}$")
    }
}

/**
 * Digest utility for computing sha256-prefixed digests.
 */
object EvidenceDigest {

    private const val PREFIX = "sha256:"

    /**
     * Computes `sha256:<hex>` from the given byte array.
     */
    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            hex.append(((byte.toInt() ushr 4) and 0x0F).toString(16))
            hex.append((byte.toInt() and 0x0F).toString(16))
        }
        return PREFIX + hex.toString()
    }

    /**
     * Computes `sha256:<hex>` from a UTF-8 string.
     */
    fun sha256(content: String): String =
        sha256(content.toByteArray(Charsets.UTF_8))
}
