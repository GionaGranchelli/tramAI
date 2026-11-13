package dev.tramai.core.identity

/**
 * Maximum length of an identity value. Identity strings are opaque identifiers,
 * not human-readable descriptions: bounded length protects persistence and
 * projection surfaces without imposing a restrictive character grammar.
 */
internal const val ID_MAX_LENGTH: Int = 128

/**
 * Maximum length of an [owner][WorkloadMetadata.owner] value.
 */
internal const val OWNER_MAX_LENGTH: Int = 256

/**
 * Maximum length of a [purpose][WorkloadMetadata.purpose] value.
 */
internal const val PURPOSE_MAX_LENGTH: Int = 512

/**
 * Fail-closed validation shared by every typed identity value.
 *
 * Rules are intentionally conservative:
 * - non-blank;
 * - no leading or trailing whitespace (no silent normalization on the way in);
 * - bounded length;
 * - no ISO control characters.
 *
 * Case is preserved and no character grammar is imposed: identifiers such as
 * `payments`, `Payments`, `org.example.claims`, `urn:company:workload:claims`,
 * `production-eu` and `deployment_2026_09` are all legal distinct values.
 * Silent canonicalization (e.g. trimming, lowercasing, regex shaping) is
 * deliberately absent: identity systems become dangerous when different inputs
 * silently collapse to the same key.
 */
internal fun validateIdentity(
    kind: String,
    value: String,
) {
    require(value.isNotBlank()) { "$kind must not be blank" }
    require(value == value.trim()) { "$kind must not contain leading or trailing whitespace" }
    require(value.length <= ID_MAX_LENGTH) { "$kind must not exceed $ID_MAX_LENGTH characters" }
    require(value.none(Char::isISOControl)) { "$kind must not contain control characters" }
}
