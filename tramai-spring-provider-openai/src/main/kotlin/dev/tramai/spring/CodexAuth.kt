package dev.tramai.spring

/**
 * Codex ChatGPT auth-file settings, shared by the OpenAI and OpenAI-compatible
 * property models.
 *
 * Experimental: intended for local testing and exploratory integrations.
 */
data class CodexAuth(
    var enabled: Boolean = false,
    var authFile: String? = null,
)
