package dev.tramai.core.security

/**
 * Security configuration for a workflow step.
 *
 * Three modes:
 * - [Default]: Use all default defenses (sanitizer, validator, instruction defense).
 * - [Custom]: Override specific defenses while keeping defaults for null fields.
 * - [Disabled]: Bypass all framework defense layers entirely (auditable).
 */
sealed class StepSecurityConfig {
    /** Use all default defenses. */
    data object Default : StepSecurityConfig()

    /**
     * Partial or full override of default defenses.
     *
     * Any null field falls through to the default implementation.
     *
     * @param sanitizer Custom prompt sanitizer. Null uses [dev.tramai.orchestration.DefaultPromptSanitizer].
     * @param validator Custom output validator. Null uses [dev.tramai.orchestration.DefaultOutputValidator].
     * @param instructionDefense Custom instruction defense. Null uses [dev.tramai.orchestration.DefaultInstructionDefense].
     * @param customSystemInstructions Appended to the base system instructions. Only used when [instructionDefense] is null.
     * @param validatorPatterns Custom validator regex patterns. **Replaces** (does not extend) the default pattern set.
     *                          Only used when [validator] is null. Each pattern string is compiled as a case-insensitive Regex.
     */
    data class Custom(
        val sanitizer: PromptSanitizer? = null,
        val validator: OutputValidator? = null,
        val instructionDefense: InstructionDefense? = null,
        val customSystemInstructions: String? = null,
        val validatorPatterns: List<String>? = null,
    ) : StepSecurityConfig()
    data object Disabled : StepSecurityConfig()
}
