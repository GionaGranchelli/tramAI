package dev.tramai.core.approval

/**
 * Shared actor identity policy for all actor-bearing approval boundaries.
 *
 * Actor identifiers are trusted stable references, not free-form text.
 * Applications must never pass credentials, tokens, or sensitive content as actor IDs.
 *
 * Input validation (throws IllegalArgumentException):
 *   [validateActorId] — applied at store and coordinator entry points before
 *   token consumption or state mutation.
 *
 * Defense-in-depth normalization (returns sentinel on mismatch):
 *   [safeActorId] — applied by lifecycle emitter before durable audit persistence.
 *
 * Allowed characters: alphanumeric, dot, underscore, colon, at-sign, plus, hyphen.
 * First character must be alphanumeric. Maximum length 256.
 */
public object SafeActorIdPolicy {

    private const val MAX_ACTOR_ID_LENGTH = 256

    public val SAFE_ACTOR_ID: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")

    /**
     * Validates an actor identifier at input boundaries.
     * Throws [IllegalArgumentException] if the value does not match the safe pattern.
     */
    public fun validateActorId(value: String, fieldName: String = "actorId"): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
        require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(trimmed.length <= MAX_ACTOR_ID_LENGTH) { "$fieldName exceeds maximum length of $MAX_ACTOR_ID_LENGTH" }
        require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
        require(SAFE_ACTOR_ID.matches(value)) {
            "$fieldName must match safe pattern: alphanumeric start, alphanumeric + ._:@+- allowed"
        }
        return trimmed
    }

    /**
     * Defense-in-depth normalization for durable audit persistence.
     * Valid values pass through unchanged. Invalid values are redacted to a sentinel.
     */
    public fun safeActorId(raw: String): String =
        raw.takeIf { it.length <= MAX_ACTOR_ID_LENGTH && SAFE_ACTOR_ID.matches(it) }
            ?: "approval_actor_redacted"
}
