package dev.tramai.security

/**
 * Opt-in capability of a [dev.tramai.core.policy.PolicyEngine] that carries the configured routing
 * topology.
 *
 * The engine consumes this as an optional capability rather than extracting a concrete type: a
 * custom policy engine may opt into the same topology contract instead of being structurally
 * excluded for not being a particular implementation.
 *
 * The absence of this capability means no topology, therefore no governed provider candidate, and
 * execution fails closed. It never means "fall back to configured routing": 0.7.3 has no path that
 * invokes a provider without a candidate selected from a viable envelope.
 */
interface ProviderRoutingConfigurationSource {
    val providerRoutingConfiguration: ProviderRoutingConfiguration
}
