@file:OptIn(ExperimentalProviderTransportApi::class)

package dev.tramai.core.util

import dev.tramai.core.provider.transport.ExperimentalProviderTransportApi
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.InetAddress
import java.net.URI
import kotlin.test.Test

class OutboundAddressSecurityTest {
    @Test
    fun `canonicalizeOutboundHost normalizes valid hostnames`() {
        assertThat(OutboundAddressSecurity.canonicalizeOutboundHost("EXAMPLE.COM"))
            .isEqualTo("example.com")
        assertThat(OutboundAddressSecurity.canonicalizeOutboundHost("example.com."))
            .isEqualTo("example.com")
        assertThat(OutboundAddressSecurity.canonicalizeOutboundHost("[2001:db8::1]"))
            .isEqualTo("2001:db8::1")
        assertThat(OutboundAddressSecurity.canonicalizeOutboundHost("xn--mller-kva.de"))
            .isEqualTo("xn--mller-kva.de")
    }

    @Test
    fun `canonicalizeOutboundHost parses alternative IPv4 literals`() {
        assertThat(OutboundAddressSecurity.canonicalizeOutboundHost("0177.0.0.1"))
            .isEqualTo("127.0.0.1")
        assertThat(OutboundAddressSecurity.canonicalizeOutboundHost("0x7f000001"))
            .isEqualTo("127.0.0.1")
        assertThat(OutboundAddressSecurity.canonicalizeOutboundHost("2130706433"))
            .isEqualTo("127.0.0.1")
        assertThat(OutboundAddressSecurity.canonicalizeOutboundHost("127.1"))
            .isEqualTo("127.0.0.1")
    }

    @Test
    fun `canonicalizeOutboundHost throws on empty or invalid host`() {
        assertThatThrownBy { OutboundAddressSecurity.canonicalizeOutboundHost("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { OutboundAddressSecurity.canonicalizeOutboundHost("[]") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `isPrivateOrRestrictedAddress correctly identifies restricted IPv4 addresses`() {
        val restrictedIpv4s =
            listOf(
                "127.0.0.1",
                "127.10.20.30",
                "10.0.0.1",
                "10.255.255.255",
                "172.16.0.1",
                "172.31.255.255",
                "192.168.0.1",
                "192.168.255.255",
                "169.254.169.254",
                "169.254.1.1",
                "100.64.0.1",
                "100.127.255.254",
                "0.0.0.0",
                "224.0.0.1",
            )
        for (ip in restrictedIpv4s) {
            val addr = InetAddress.getByName(ip)
            assertThat(OutboundAddressSecurity.isPrivateOrRestrictedAddress(addr))
                .`as`("Expected $ip to be restricted")
                .isTrue()
        }
    }

    @Test
    fun `isPrivateOrRestrictedAddress correctly identifies restricted IPv6 addresses`() {
        val restrictedIpv6s =
            listOf(
                "::1",
                "::",
                "fe80::1",
                "fc00::1",
                "fd00::1",
                "ff02::1",
            )
        for (ip in restrictedIpv6s) {
            val addr = InetAddress.getByName(ip)
            assertThat(OutboundAddressSecurity.isPrivateOrRestrictedAddress(addr))
                .`as`("Expected $ip to be restricted")
                .isTrue()
        }
    }

    @Test
    fun `isPrivateOrRestrictedAddress allows public IP addresses`() {
        val publicIps =
            listOf(
                "8.8.8.8",
                "1.1.1.1",
                "93.184.216.34", // example.com
                "2606:2800:220:1:248:1893:25c8:1946", // example.com IPv6
            )
        for (ip in publicIps) {
            val addr = InetAddress.getByName(ip)
            assertThat(OutboundAddressSecurity.isPrivateOrRestrictedAddress(addr))
                .`as`("Expected $ip to be public/unrestricted")
                .isFalse()
        }
    }

    @Test
    fun `validateOutboundUri rejects non-http schemes`() {
        assertThatThrownBy { OutboundAddressSecurity.validateOutboundUri(URI("file:///etc/hosts")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unsupported outbound scheme")

        assertThatThrownBy { OutboundAddressSecurity.validateOutboundUri(URI("jar:file:/app.jar!/data")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unsupported outbound scheme")
    }

    @Test
    fun `validateOutboundUri rejects user info in URI authority`() {
        assertThatThrownBy {
            OutboundAddressSecurity.validateOutboundUri(URI("http://user:pass@93.184.216.34/photo.png"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("outbound URI must not contain user info")

        assertThatThrownBy {
            OutboundAddressSecurity.validateOutboundUri(URI("http://admin@93.184.216.34/photo.png"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("outbound URI must not contain user info")
    }

    @Test
    fun `validateOutboundUri rejects localhost and restricted destinations`() {
        assertThatThrownBy { OutboundAddressSecurity.validateOutboundUri(URI("http://localhost/test")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("restricted")

        assertThatThrownBy { OutboundAddressSecurity.validateOutboundUri(URI("http://127.0.0.1/test")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("restricted")

        assertThatThrownBy { OutboundAddressSecurity.validateOutboundUri(URI("http://169.254.169.254/metadata")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("restricted")
    }

    @Test
    fun `validateOutboundUri accepts public IP literals`() {
        OutboundAddressSecurity.validateOutboundUri(URI("http://93.184.216.34/image.png"))
        OutboundAddressSecurity.validateOutboundUri(URI("https://8.8.8.8/test"))
    }

    @Test
    fun `validateOutboundUri allows private destinations when explicitly enabled`() {
        OutboundAddressSecurity.validateOutboundUri(URI("http://127.0.0.1/test"), allowPrivateDestinations = true)
        OutboundAddressSecurity.validateOutboundUri(URI("http://localhost/test"), allowPrivateDestinations = true)
        OutboundAddressSecurity.validateOutboundUri(
            URI("http://192.168.1.1:8080/test"),
            allowPrivateDestinations = true,
        )
    }

    @Test
    fun `validateOutboundUri throws on unresolvable host`() {
        assertThatThrownBy {
            val unresolvable = URI("http://invalid-non-existent-domain-123456789.example/test")
            OutboundAddressSecurity.validateOutboundUri(unresolvable)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("failed to resolve")
    }

    @Test
    fun `extractHost extracts host from various URI formats`() {
        assertThat(OutboundAddressSecurity.extractHost(URI("http://example.com/path"))).isEqualTo("example.com")
        val uriWithAuth = URI("http://user:pass@example.com:8080/path")
        assertThat(OutboundAddressSecurity.extractHost(uriWithAuth)).isEqualTo("example.com")
        assertThat(OutboundAddressSecurity.extractHost(URI("http://127.1/path"))).isEqualTo("127.1")
        assertThat(OutboundAddressSecurity.extractHost(URI("http://0x7f000001/path"))).isEqualTo("0x7f000001")

        assertThatThrownBy { OutboundAddressSecurity.extractHost(URI("http:///path")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("valid host")
    }

    @Test
    fun `parseAlternativeIpv4Literal handles all component lengths and invalid inputs`() {
        // 1 part
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("2130706433")?.hostAddress)
            .isEqualTo("127.0.0.1")
        // 2 parts
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("127.1")?.hostAddress)
            .isEqualTo("127.0.0.1")
        // 3 parts
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("127.0.1")?.hostAddress)
            .isEqualTo("127.0.0.1")
        // 4 parts
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("127.0.0.1")?.hostAddress)
            .isEqualTo("127.0.0.1")
        // 4 parts hex
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("0x7f.0.0.1")?.hostAddress)
            .isEqualTo("127.0.0.1")
        // 4 parts octal
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("0177.0.0.1")?.hostAddress)
            .isEqualTo("127.0.0.1")

        // Non-IPv4 or invalid
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("::1")).isNull()
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("1.2.3.4.5")).isNull()
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("1.2.3.999")).isNull()
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("1.2.99999999")).isNull()
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("999999999999")).isNull()
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("not.an.ip.address")).isNull()
        assertThat(OutboundAddressSecurity.parseAlternativeIpv4Literal("1..2.3")).isNull()
    }
}
