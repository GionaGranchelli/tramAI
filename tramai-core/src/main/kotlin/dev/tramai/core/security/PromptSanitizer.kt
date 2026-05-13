package dev.tramai.core.security

fun interface PromptSanitizer {
    fun sanitize(input: String): String
}
