@file:Suppress(
    "MagicNumber",
    "MaxLineLength",
    "ComplexMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "ThrowsCount",
    "TooGenericExceptionCaught",
)

package dev.tramai.core.util

import dev.tramai.core.provider.transport.ExperimentalProviderTransportApi
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Authoritative address classification and host canonicalization utilities for
 * outbound network operations across TramAI.
 *
 * Enforces restricted-address rejection for SSRF protection:
 * - Loopback (127.0.0.0/8, ::1)
 * - Link-local (169.254.0.0/16, fe80::/10)
 * - Site-local / RFC 1918 private IPv4 (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fec0::/10)
 * - Carrier-Grade NAT / RFC 6598 (100.64.0.0/10)
 * - Unique-local IPv6 / RFC 4193 (fc00::/7)
 * - Any-local (0.0.0.0, ::)
 * - Multicast (224.0.0.0/4, ff00::/8)
 */
@ExperimentalProviderTransportApi
object OutboundAddressSecurity {
    private const val LOCALHOST_HOSTNAME = "localhost"

    /**
     * Returns true if [address] is private, loopback, link-local, carrier-grade NAT,
     * unique-local IPv6, multicast, or any-local.
     */
    fun isPrivateOrRestrictedAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress ||
            address.isCarrierGradeNatIpv4() ||
            address.isUniqueLocalIpv6()

    /**
     * Canonicalizes an outbound host string:
     * - Strips surrounding `[` and `]`, trailing `.`
     * - Converts IDN domain names to ASCII
     * - Parses alternative IPv4 notations (octal, hex, dword) to canonical dotted-decimal
     * - Normalizes to lowercase
     */
    fun canonicalizeOutboundHost(host: String): String {
        val unbracketed = host.removePrefix("[").removeSuffix("]").removeSuffix(".")
        require(unbracketed.isNotEmpty()) { "outbound hostname must not be empty" }
        val asciiHost =
            try {
                if (unbracketed.contains(':')) unbracketed else IDN.toASCII(unbracketed, IDN.ALLOW_UNASSIGNED)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("invalid outbound hostname: $host", error)
            }
        return parseAlternativeIpv4Literal(asciiHost)?.hostAddress ?: asciiHost.lowercase()
    }

    /**
     * Resolves all IP addresses for [host]. If [host] is an IP literal or alternative
     * notation, returns a single-element list with the parsed IP address.
     */
    fun resolveHostAddresses(host: String): List<InetAddress> {
        parseAlternativeIpv4Literal(host)?.let { return listOf(it) }
        return InetAddress.getAllByName(host).distinctBy { it.hostAddress }
    }

    /**
     * Extracts the host string from [uri].
     *
     * Java's [URI.getHost] returns null for non-canonical IPv4 literals (e.g. `127.1`,
     * `0x7f000001`, `0177.0.0.1`) even though the authority is a valid reg-name. This
     * method recovers the literal from the raw authority when [URI.getHost] returns null.
     */
    fun extractHost(uri: URI): String {
        val host = uri.host
        if (!host.isNullOrBlank()) {
            return host
        }
        val authority = uri.rawAuthority ?: uri.authority
        if (!authority.isNullOrBlank()) {
            val userStripped = if (authority.contains('@')) authority.substringAfter('@') else authority
            val hostCandidate = userStripped.substringBefore(':').removeSuffix(".")
            if (hostCandidate.isNotEmpty()) {
                return hostCandidate
            }
        }
        throw IllegalArgumentException("outbound URI must contain a valid host: $uri")
    }

    /**
     * Validates that [uri] uses an allowed HTTP/HTTPS scheme and resolves strictly
     * to non-restricted, public IP addresses.
     *
     * @throws IllegalArgumentException if the scheme is not http/https or if the destination
     *         resolves to a restricted IP address or localhost.
     */
    fun validateOutboundUri(uri: URI) {
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "unsupported outbound scheme: ${uri.scheme} (only http and https are permitted)"
        }
        val rawHost = extractHost(uri)
        val canonicalHost = canonicalizeOutboundHost(rawHost)
        require(canonicalHost != LOCALHOST_HOSTNAME) {
            "outbound host is restricted: $rawHost"
        }
        val addresses =
            try {
                resolveHostAddresses(canonicalHost)
            } catch (error: Exception) {
                throw IllegalArgumentException("failed to resolve outbound host: $rawHost", error)
            }
        require(addresses.isNotEmpty()) {
            "outbound host did not resolve to any address: $rawHost"
        }
        val restricted = addresses.firstOrNull(::isPrivateOrRestrictedAddress)
        require(restricted == null) {
            "outbound host resolves to a restricted address: $rawHost ($restricted)"
        }
    }

    /**
     * Parses alternative IPv4 literal representations (hex, octal, dword, dotted-hex, etc.).
     */
    fun parseAlternativeIpv4Literal(host: String): InetAddress? {
        if (host.contains(':')) {
            return null
        }
        val components = host.split('.')
        if (components.isEmpty() || components.size > 4) {
            return null
        }
        val values = components.map { component -> parseIpv4Component(component) ?: return null }
        val rawAddress =
            when (values.size) {
                1 -> {
                    values.single().takeIf { it in 0L..0xffff_ffffL }
                }

                2 -> {
                    values[0].takeIf { it in 0L..0xffL }?.let { first ->
                        values[1].takeIf { it in 0L..0x00ff_ffffL }?.let { second ->
                            (first shl 24) or second
                        }
                    }
                }

                3 -> {
                    values[0].takeIf { it in 0L..0xffL }?.let { first ->
                        values[1].takeIf { it in 0L..0xffL }?.let { second ->
                            values[2].takeIf { it in 0L..0xffffL }?.let { third ->
                                (first shl 24) or (second shl 16) or third
                            }
                        }
                    }
                }

                4 -> {
                    values.takeIf { parts -> parts.all { it in 0L..0xffL } }?.let { parts ->
                        (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
                    }
                }

                else -> {
                    null
                }
            } ?: return null

        val bytes =
            byteArrayOf(
                ((rawAddress ushr 24) and 0xff).toByte(),
                ((rawAddress ushr 16) and 0xff).toByte(),
                ((rawAddress ushr 8) and 0xff).toByte(),
                (rawAddress and 0xff).toByte(),
            )
        return InetAddress.getByAddress(bytes)
    }

    private fun parseIpv4Component(component: String): Long? {
        if (component.isEmpty()) return null
        return try {
            when {
                component.startsWith("0x", ignoreCase = true) -> component.substring(2).toLong(16)
                component.startsWith("0") && component.length > 1 -> component.substring(1).toLong(8)
                else -> component.toLong(10)
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun InetAddress.isCarrierGradeNatIpv4(): Boolean {
        if (this !is Inet4Address) return false
        val bytes = address
        return bytes[0].toInt() and 0xff == 100 &&
            (bytes[1].toInt() and 0b1100_0000) == 0b0100_0000
    }

    private fun InetAddress.isUniqueLocalIpv6(): Boolean = this is Inet6Address && address[0].toInt() and 0xfe == 0xfc
}
