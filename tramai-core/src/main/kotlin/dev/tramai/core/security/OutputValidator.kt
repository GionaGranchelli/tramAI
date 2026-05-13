package dev.tramai.core.security

fun interface OutputValidator {
    fun validate(output: String): ValidationResult
}
