package dev.tramai.spring.sovereign.ops.outbox

/**
 * 0.7.6f §6: an emission that cannot succeed by retrying.
 *
 * Carries the error code the dispatcher records, so the persisted `last_error_code` names the real cause
 * instead of an exception class name.
 */
internal sealed class EvidenceFailure(
    val errorCode: String,
    cause: Throwable? = null,
) : RuntimeException(errorCode, cause) {
    /** The payload decodes, but this deployment cannot represent its kind. */
    class UnsupportedKind(
        message: String,
        cause: Throwable? = null,
    ) : EvidenceFailure(ERROR_UNSUPPORTED_EVIDENCE_KIND, cause) {
        init {
            require(message.isNotBlank()) { "unsupported-evidence-kind detail" }
        }
    }

    /** A legacy record whose persisted state cannot name a kind. Never guessed, never emitted. */
    class UndecodableLegacy : EvidenceFailure(ERROR_UNDECODABLE_LEGACY_EVIDENCE)
}

internal const val ERROR_UNSUPPORTED_EVIDENCE_KIND: String = "unsupported-evidence-kind"
internal const val ERROR_UNDECODABLE_LEGACY_EVIDENCE: String = "undecodable-legacy-evidence"
