package dev.tramai.core.policy

/**
 * Security-related enums shared across policy, engine, and security modules.
 */

enum class DataClassification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED,
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class ApprovalMode {
    /** No human approval required. */
    AUTO,
    /** Execution suspended until approval is granted. */
    HUMAN_REQUIRED,
    /** Execution suspended with a configurable timeout; auto-deny on expiry. */
    HUMAN_REQUIRED_WITH_TIMEOUT,
}

/**
 * Application-level egress policy for TramAI-managed destinations
 * (providers, HTTP tools, declared endpoints).
 *
 * Infrastructure-level controls (firewall, NetworkPolicy, sandboxing)
 * remain required for subprocesses, native code, and compromised dependencies.
 */
enum class ManagedNetworkEgress {
    /** Managed HTTP connections may reach configured destinations. */
    ALLOW,
    /** TramAI-managed HTTP connections are denied. */
    DENY,
    /** Managed HTTP connections may reach allowlisted destinations only. */
    ALLOWLIST_ONLY,
}

enum class AuditDetail {
    /** Event ID, decision, and timestamp only. */
    MINIMAL,
    /** Full decision context, no payload data. */
    DECISION_ONLY,
    /** Decision context plus payload metadata. */
    FULL,
}

enum class ProviderPolicy {
    /**
     * Only organization-controlled inference endpoints inside the configured
     * local or isolated trust boundary (same-host Ollama/vLLM, internal GPU
     * nodes, private inference endpoints in isolated namespaces).
     */
    LOCAL_ONLY,
    /**
     * Only approved providers explicitly classified as EU-hosted by the
     * organization-managed provider registry. TramAI does not independently
     * certify legal jurisdiction.
     */
    EU_ONLY,
    /** Only cloud providers on the approved list. */
    APPROVED_CLOUD,
    /** Any provider in the approved registry. */
    ANY_APPROVED,
}

enum class ClassificationSource {
    /** Classification was provided explicitly by the caller. */
    DECLARED,
    /** Classification was determined by rules (regex, metadata, DLP). */
    RULE_BASED,
    /** Classification was proposed by an approved local model. */
    LOCAL_MODEL_ASSISTED,
}

enum class PolicyMode {
    /** Fail closed if policy module is absent. */
    SECURE,
    /** Allow all operations — explicit opt-in for backward compatibility. */
    LEGACY_PERMISSIVE,
}

enum class CompatibilityMode {
    /** Tool metadata has been explicitly configured for the current profile. */
    STRICT,
    /** Tool uses legacy-permissive defaults; rejected in secure profiles. */
    LEGACY_PERMISSIVE,
}
