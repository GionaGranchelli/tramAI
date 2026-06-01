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

enum class NetworkEgress {
    /** Tool may open network connections to any destination. */
    ALLOW,
    /** Tool must not open any network connection. */
    DENY,
    /** Tool may only connect to destinations on the configured allowlist. */
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
    /** Only local providers (Ollama, vLLM on localhost). */
    LOCAL_ONLY,
    /** Only providers hosted within EU jurisdiction. */
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
