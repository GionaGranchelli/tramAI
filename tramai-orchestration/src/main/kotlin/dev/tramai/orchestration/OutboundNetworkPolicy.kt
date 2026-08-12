package dev.tramai.orchestration

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Canonical, policy-ready view of an outbound HTTP target. Canonicalization happens
 * before policy evaluation: scheme lowercased, host ASCII-lowercased without trailing
 * dot or IPv6 brackets, IDN converted to ASCII. [addresses] are the pre-resolved
 * addresses (defence-in-depth — NOT a guarantee the transport connects to them) plus,
 * when the transport can prove it, the actual connected address.
 */
data class OutboundNetworkTarget(
    val scheme: String,
    val host: String,
    val port: Int?,
    val addresses: List<InetAddress>,
    val allowedHostnames: Set<String>?,
)

/**
 * Decides whether an outbound HTTP target may be contacted. Implementations own
 * scheme allowlisting, hostname allowlisting, and prohibited-address-range rejection.
 * Throw an IllegalArgumentException (or subtype) to reject; a rejection maps to
 * WorkflowStepFailureCode.POLICY_REJECTED with a fixed safe public message.
 */
interface OutboundNetworkPolicy {
    fun validateTarget(target: OutboundNetworkTarget)
}

/**
 * Policy factories.
 *
 * Hostname allowlisting and private-destination permission are SEPARATE concepts:
 * an allowlisted hostname does NOT lift restricted-address filtering. Private,
 * loopback, link-local, CGNAT, IPv6-ULA and metadata destinations are rejected
 * unless [allowPrivateDestinations] is explicitly true.
 */
object OutboundNetworkPolicies {
    /** Default defence-in-depth: optional per-step hostname allowlist, restricted-address filtering active. */
    fun defenceInDepth(allowPrivateDestinations: Boolean = false): OutboundNetworkPolicy =
        DefaultOutboundNetworkPolicy(allowedHosts = null, allowPrivateDestinations = allowPrivateDestinations)

    /**
     * Governed/strict policy: hostname allowlist MANDATORY (require non-empty at construction),
     * restricted-address filtering active, private destinations require [allowPrivateDestinations].
     * The governed allowlist overrides any per-step HttpStepConfig.allowedHosts.
     */
    fun governed(allowedHosts: Set<String>, allowPrivateDestinations: Boolean = false): OutboundNetworkPolicy {
        require(allowedHosts.isNotEmpty()) { "Governed outbound network policy requires at least one allowed hostname" }
        return DefaultOutboundNetworkPolicy(allowedHosts.map(::canonicalizeOutboundHost).toSet(), allowPrivateDestinations)
    }
}

private class DefaultOutboundNetworkPolicy(
    private val allowedHosts: Set<String>?,
    private val allowPrivateDestinations: Boolean,
) : OutboundNetworkPolicy {
    override fun validateTarget(target: OutboundNetworkTarget) {
        require(target.scheme in allowedHttpSchemes) { "unsupported outbound HTTP scheme: ${target.scheme}" }
        val effectiveAllowedHosts = allowedHosts ?: target.allowedHostnames
        require(effectiveAllowedHosts == null || target.host in effectiveAllowedHosts) {
            "outbound host is not in the allowlist: ${target.host}"
        }
        require(allowPrivateDestinations ||
            (target.host != localhostHostName && target.addresses.none(::isPrivateOrRestrictedAddress))) {
            "outbound host resolves to a restricted address: ${target.host}"
        }
    }
}

internal fun isPrivateOrRestrictedAddress(address: InetAddress): Boolean = address.isAnyLocalAddress ||
    address.isLoopbackAddress ||
    address.isLinkLocalAddress ||
    address.isSiteLocalAddress ||
    address.isCarrierGradeNatIpv4() ||
    address.isUniqueLocalIpv6()

private fun InetAddress.isCarrierGradeNatIpv4(): Boolean {
    if (this !is Inet4Address) return false
    val bytes = address
    return bytes[0].toInt() and 0xff == 100 &&
        (bytes[1].toInt() and 0b1100_0000) == 0b0100_0000
}

private fun InetAddress.isUniqueLocalIpv6(): Boolean =
    this is Inet6Address && address[0].toInt() and 0xfe == 0xfc
