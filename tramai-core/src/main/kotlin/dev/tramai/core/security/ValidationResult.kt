package dev.tramai.core.security

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Rejected(
        val reason: String,
        val ruleId: String? = null,
    ) : ValidationResult()
}
