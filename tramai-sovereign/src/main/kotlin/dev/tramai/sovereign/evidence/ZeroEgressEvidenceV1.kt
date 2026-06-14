package dev.tramai.sovereign.evidence

/**
 * Summarises the zero-egress verification result from the offline harness.
 *
 * Carries probe-level attestation: whether the loopback model provider
 * was invoked successfully and whether external TCP / DNS probes
 * were blocked as expected.
 *
 * @property deploymentMode The deployment mode under which the test ran.
 * @property runtimeBuildSucceeded Whether the sovereign runtime was built successfully.
 * @property loopbackProviderInvocationSucceeded Whether the loopback provider returned a valid response.
 * @property loopbackProviderInvocationCount Number of times the loopback provider was invoked.
 * @property externalTcpProbeBlocked Whether an external TCP connect (1.1.1.1:443) was blocked.
 * @property externalDnsProbeBlocked Whether an external DNS resolution (example.com) was blocked.
 */
data class ZeroEgressEvidenceV1(
    val deploymentMode: String,
    val runtimeBuildSucceeded: Boolean,
    val loopbackProviderInvocationSucceeded: Boolean,
    val loopbackProviderInvocationCount: Int,
    val externalTcpProbeBlocked: Boolean,
    val externalDnsProbeBlocked: Boolean,
)
