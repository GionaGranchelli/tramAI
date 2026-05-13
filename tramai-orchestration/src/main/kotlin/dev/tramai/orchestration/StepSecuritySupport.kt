package dev.tramai.orchestration

import dev.tramai.core.security.OutputValidator
import dev.tramai.core.security.StepSecurityConfig
import dev.tramai.core.security.ValidationResult

internal data class ResolvedStepSecurity(
    val defendedPrompt: String,
    val defenseActive: Boolean,
    val validator: OutputValidator?,
)

internal fun resolveStepSecurity(
    prompt: String,
    security: StepSecurityConfig,
): ResolvedStepSecurity = when (security) {
    is StepSecurityConfig.Disabled -> ResolvedStepSecurity(
        defendedPrompt = prompt,
        defenseActive = false,
        validator = null,
    )
    is StepSecurityConfig.Default -> {
        val sanitized = DefaultPromptSanitizer.sanitize(prompt)
        ResolvedStepSecurity(
            defendedPrompt = DefaultInstructionDefense().wrap(sanitized, ""),
            defenseActive = true,
            validator = DefaultOutputValidator(),
        )
    }
    is StepSecurityConfig.Custom -> {
        val sanitizer = security.sanitizer ?: DefaultPromptSanitizer
        val useDefaultDefense = security.instructionDefense == null
        val instructionDefense = if (useDefaultDefense) {
            DefaultInstructionDefense(security.customSystemInstructions)
        } else {
            security.instructionDefense!!
        }
        val sanitized = sanitizer.sanitize(prompt)
        val wrapSystemInstructions = if (useDefaultDefense) "" else (security.customSystemInstructions ?: "")
        val validator = security.validator ?: security.validatorPatterns?.let { patterns ->
            val customPatterns = patterns.map { pattern ->
                pattern to Regex(pattern, RegexOption.IGNORE_CASE)
            }
            DefaultOutputValidator(patterns = customPatterns)
        } ?: DefaultOutputValidator()
        ResolvedStepSecurity(
            defendedPrompt = instructionDefense.wrap(sanitized, wrapSystemInstructions),
            defenseActive = true,
            validator = validator,
        )
    }
}

internal fun validateStepOutput(
    output: String,
    validator: OutputValidator?,
): ValidationResult = validator?.validate(output) ?: ValidationResult.Valid
