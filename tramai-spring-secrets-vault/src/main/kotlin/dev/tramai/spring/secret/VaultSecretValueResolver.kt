package dev.tramai.spring.secret

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.secret.SecretValueResolver
import dev.tramai.spring.SpringBuiltInSecretValueResolver
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Resolves Vault-backed secret references in the form `vault:path[#field]`.
 *
 * By default, the resolver assumes a KV v2 mount and returns the `value` field
 * when no explicit `#field` selector is present.
 */
class VaultSecretValueResolver(
    private val baseUrl: String,
    private val token: String,
    private val mountPath: String = "secret",
    private val kvVersion: Int = 2,
    private val namespace: String? = null,
    private val defaultField: String = "value",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : SecretValueResolver, SpringBuiltInSecretValueResolver {
    init {
        require(baseUrl.isNotBlank()) { "Vault baseUrl must not be blank" }
        require(token.isNotBlank()) { "Vault token must not be blank" }
        require(mountPath.isNotBlank()) { "Vault mountPath must not be blank" }
        require(kvVersion == 1 || kvVersion == 2) { "Vault kvVersion must be 1 or 2" }
        require(defaultField.isNotBlank()) { "Vault defaultField must not be blank" }
    }

    override fun resolve(secretRef: String): String? {
        val reference = parse(secretRef) ?: return null
        val request = HttpRequest.newBuilder()
            .uri(URI.create(vaultPath(reference.path)))
            .header("X-Vault-Token", token)
            .apply {
                namespace?.takeIf { it.isNotBlank() }?.let { header("X-Vault-Namespace", it) }
            }
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) {
            return null
        }
        check(response.statusCode() in 200..299) {
            "Vault secret lookup failed for '${reference.path}' with HTTP ${response.statusCode()}"
        }

        val body = objectMapper.readTree(response.body())
        val payload = when (kvVersion) {
            1 -> body.path("data")
            else -> body.path("data").path("data")
        }

        return extractValue(payload, reference.field)
    }

    private fun vaultPath(secretPath: String): String {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val normalizedMountPath = mountPath.trim('/')
        val normalizedSecretPath = secretPath.trim('/')
        return when (kvVersion) {
            1 -> "$normalizedBaseUrl/v1/$normalizedMountPath/$normalizedSecretPath"
            else -> "$normalizedBaseUrl/v1/$normalizedMountPath/data/$normalizedSecretPath"
        }
    }

    private fun extractValue(payload: JsonNode, explicitField: String?): String? {
        if (payload.isMissingNode || payload.isNull) {
            return null
        }

        if (!explicitField.isNullOrBlank()) {
            return payload.path(explicitField)
                .takeIf { !it.isMissingNode && !it.isNull && it.isValueNode }
                ?.asText()
                ?.takeIf { it.isNotBlank() }
        }

        if (payload.isValueNode) {
            return payload.asText().takeIf { it.isNotBlank() }
        }

        payload.path(defaultField)
            .takeIf { !it.isMissingNode && !it.isNull && it.isValueNode }
            ?.asText()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        if (payload.isObject && payload.size() == 1) {
            val onlyEntry = payload.fields().next()
            if (onlyEntry.value.isValueNode) {
                return onlyEntry.value.asText().takeIf { it.isNotBlank() }
            }
        }

        return null
    }

    private data class VaultReference(
        val path: String,
        val field: String?,
    )

    companion object {
        private fun parse(secretRef: String): VaultReference? {
            if (!secretRef.startsWith("vault:")) {
                return null
            }

            val rawReference = secretRef.removePrefix("vault:")
            if (rawReference.isBlank()) {
                return null
            }

            val fieldSeparator = rawReference.indexOf('#')
            val path = if (fieldSeparator >= 0) rawReference.substring(0, fieldSeparator) else rawReference
            val field = if (fieldSeparator >= 0) rawReference.substring(fieldSeparator + 1).takeIf { it.isNotBlank() } else null
            return path.takeIf { it.isNotBlank() }?.let { VaultReference(path = it, field = field) }
        }
    }
}
