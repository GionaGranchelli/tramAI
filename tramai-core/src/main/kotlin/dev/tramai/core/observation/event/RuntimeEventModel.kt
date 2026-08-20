package dev.tramai.core.observation.event

import kotlin.reflect.KClass

/**
 * Runtime event domain. Each event belongs to exactly one domain.
 */
enum class RuntimeEventDomain {
    ENGINE,
    WORKFLOW,
    WORKER,
    APPROVAL,
    POLICY,
    TOOL,
    ROUTING,
    EVIDENCE,
    SCHEDULER,
}

/**
 * Sensitivity classification of an event (or of the metadata it carries).
 */
enum class RuntimeEventSensitivity {
    /** Safe for any consumer. */
    PUBLIC,
    /** Safe within the organisation. */
    INTERNAL,
    /** Contains data that must not leave the runtime without explicit consent. */
    SENSITIVE,
}

/**
 * Declared failure behaviour of the event's secondary-system processing
 * (observer/audit/evidence). Recorded as catalogue metadata in Epic 5.2;
 * enforcement is owned by Epic 5.3.
 */
enum class RuntimeEventFailurePolicy {
    FAIL_OPEN,
    FAIL_CLOSED,
}

/**
 * Instrument type of a declared runtime metric.
 */
enum class RuntimeMetricInstrumentType {
    COUNTER,
    HISTOGRAM,
}

/**
 * Typed, catalogue-owned attribute key. The key carries its canonical value
 * type; events carrying this key with a different value type are rejected by
 * the validator.
 */
data class RuntimeAttributeKey<T : Any>(val name: String, val valueType: KClass<T>)

/**
 * Centrally declared metric descriptor. OpenTelemetry adapters translate this
 * into LongCounter/DoubleHistogram etc.; the catalogue never depends on
 * OpenTelemetry types.
 */
data class RuntimeMetricDefinition(
    val name: String,
    val description: String,
    val unit: String,
    val instrumentType: RuntimeMetricInstrumentType,
    val valueType: RuntimeMetricValueType,
)

enum class RuntimeMetricValueType {
    LONG,
    DOUBLE,
}

/**
 * Immutable definition of one runtime event: identity, domain, permitted and
 * required attributes, sensitivity, audit/evidence eligibility, metric/span
 * mapping, and declared failure policy.
 */
data class RuntimeEventDefinition(
    val name: String,
    val domain: RuntimeEventDomain,
    val allowedAttributes: Set<RuntimeAttributeKey<*>>,
    val requiredAttributes: Set<RuntimeAttributeKey<*>> = emptySet(),
    val sensitivity: RuntimeEventSensitivity,
    val auditEligible: Boolean,
    val evidenceEligible: Boolean,
    val metricMapping: RuntimeMetricDefinition? = null,
    val spanEligible: Boolean = true,
    val failurePolicy: RuntimeEventFailurePolicy = RuntimeEventFailurePolicy.FAIL_OPEN,
)
