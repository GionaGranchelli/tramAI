package dev.tramai.spring.secret

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import java.net.InetSocketAddress
import kotlin.test.Test

class VaultSecretValueResolverTest {
    @Test
    fun `returns the default field from a kv2 secret payload`() {
        var capturedToken = ""
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/secret/data/providers/openai/api-key") { exchange ->
            capturedToken = exchange.requestHeaders.getFirst("X-Vault-Token")
            val body = """
                {
                  "data": {
                    "data": {
                      "value": "resolved-vault-key"
                    }
                  }
                }
            """.trimIndent()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()

        try {
            val resolver = VaultSecretValueResolver(
                baseUrl = "http://localhost:${server.address.port}",
                token = "vault-token",
            )

            val resolved = resolver.resolve("vault:providers/openai/api-key")

            assertThat(capturedToken).isEqualTo("vault-token")
            assertThat(resolved).isEqualTo("resolved-vault-key")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `returns an explicit field selector from a vault reference`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/secret/data/providers/openai/api-key") { exchange ->
            val body = """
                {
                  "data": {
                    "data": {
                      "apiKey": "explicit-vault-key",
                      "other": "ignored"
                    }
                  }
                }
            """.trimIndent()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()

        try {
            val resolver = VaultSecretValueResolver(
                baseUrl = "http://localhost:${server.address.port}",
                token = "vault-token",
            )

            val resolved = resolver.resolve("vault:providers/openai/api-key#apiKey")

            assertThat(resolved).isEqualTo("explicit-vault-key")
        } finally {
            server.stop(0)
        }
    }
}
