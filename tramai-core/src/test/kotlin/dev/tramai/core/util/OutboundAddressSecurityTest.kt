package dev.tramai.core.util

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
    fun `canonicalizeOutboundHost throws on empty host`() {
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
}
