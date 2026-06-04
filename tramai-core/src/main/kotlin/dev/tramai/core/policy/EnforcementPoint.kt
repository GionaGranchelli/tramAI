package dev.tramai.core.policy

/**
 * Mandatory enforcement points where the engine invokes [PolicyEngine.evaluate].
 *
 * These are called from [TramaiEngine] internals — never from optional interceptor chains.
 * There is no code path that reaches a provider or tool executor without passing
 * through the policy engine at the relevant enforcement point.
 */
enum class EnforcementPoint {
    /** Hash-chained audit evidence for authoritative model-output DLP redactions. */
    DLP_MODEL_OUTPUT,
    /** Hash-chained audit evidence for authoritative tool-result DLP redactions. */
    DLP_TOOL_RESULT,
    /** Before model resolution and allowlist check. */
    BEFORE_PROVIDER_RESOLUTION,
    /** Before the actual provider HTTP/stream call. */
    BEFORE_PROVIDER_INVOCATION,
    /** Before falling back to an alternative provider. */
    BEFORE_FALLBACK,
    /** Before tool definitions are exposed in the model request. */
    BEFORE_TOOL_EXPOSURE,
    /** Before tool execution starts. */
    BEFORE_TOOL_EXECUTION,
    /** Before tool results are reinjected into the model context. */
    BEFORE_TOOL_RESULT_REINJECTION,
    /** Before the final response is returned to the caller. */
    BEFORE_RESPONSE_RETURN,
    /** Before a suspended workflow is resumed. */
    BEFORE_WORKFLOW_RESUME,
}
