package dev.tramai.examples.offline

/**
 * Schema-versioned report for zero-egress offline verification.
 *
 * Captures deployment configuration, runtime build result,
 * loopback provider invocation metrics, external network probe
 * results, and security state (artifact verification, audit chain).
 */
data class ZeroEgressVerificationReportV1(
    val schemaVersion: Int = 1,
    val deploymentMode: String,
    val runtimeBuildSucceeded: Boolean,
    val loopbackProviderInvocationSucceeded: Boolean,
    val loopbackProviderInvocationCount: Int,
    val externalTcpProbeBlocked: Boolean,
    val externalDnsProbeBlocked: Boolean,
    val configuredProviderZones: Map<String, String>,
    val artifactVerificationReceiptCount: Int,
    val auditChainValid: Boolean,
)
