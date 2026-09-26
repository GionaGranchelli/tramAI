package dev.tramai.core.identity

/**
 * Bounded, safe operational metadata about a governed workload.
 *
 * [owner] and [purpose] describe responsibility and intent. They are NOT part
 * of identity equality: ownership may change (e.g. from one team to another)
 * without creating a different workload identity. The authoritative association
 * between a [WorkloadDeploymentIdentity] and its metadata belongs to the
 * registration/state authority (Epic 0.7.1 candidate 0.7.1c).
 *
 * This type is safe operational metadata only. It is NOT a place for prompts,
 * credentials, PII, tool arguments, user content or secrets. A free-form
 * `Map<String, Any?>` is deliberately not provided: it would recreate the
 * untyped attribute problem identified by the 0.7.1a audit.
 */
data class WorkloadMetadata(
    val owner: String,
    val purpose: String,
) {
    init {
        require(owner.isNotBlank()) { "owner must not be blank" }
        require(owner == owner.trim()) { "owner must not contain leading or trailing whitespace" }
        require(owner.length <= OWNER_MAX_LENGTH) { "owner must not exceed $OWNER_MAX_LENGTH characters" }
        require(owner.none(Char::isISOControl)) { "owner must not contain control characters" }

        require(purpose.isNotBlank()) { "purpose must not be blank" }
        require(purpose == purpose.trim()) { "purpose must not contain leading or trailing whitespace" }
        require(purpose.length <= PURPOSE_MAX_LENGTH) { "purpose must not exceed $PURPOSE_MAX_LENGTH characters" }
        require(purpose.none(Char::isISOControl)) { "purpose must not contain control characters" }
    }
}
