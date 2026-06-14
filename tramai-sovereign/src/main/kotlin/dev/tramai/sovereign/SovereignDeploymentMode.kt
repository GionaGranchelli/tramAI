package dev.tramai.sovereign

/**
 * Declares the deployment connectivity contract for the sovereign runtime.
 *
 * STANDARD:
 *   Existing sovereign routing behavior. Explicitly allowed LOCAL, EU_CLOUD,
 *   and GLOBAL_CLOUD providers may be configured according to policy.
 *
 * OFFLINE:
 *   Local-only runtime composition. Every registered, primary, fallback,
 *   and default provider route must target [ProviderTrustZone.LOCAL].
 *
 * This enum does not claim to enforce infrastructure-level network isolation.
 * Production offline deployments still require firewall, container, proxy,
 * NetworkPolicy, sandbox, or physical air-gap controls.
 *
 * @see SovereignProfileConfiguration
 * @see SovereignTramai.Builder
 */
enum class SovereignDeploymentMode {
    STANDARD,
    OFFLINE,
}
