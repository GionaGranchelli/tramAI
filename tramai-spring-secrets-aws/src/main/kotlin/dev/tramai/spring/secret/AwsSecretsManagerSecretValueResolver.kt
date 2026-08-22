package dev.tramai.spring.secret

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.secret.SecretValueResolver
import dev.tramai.spring.SpringBuiltInSecretValueResolver
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException
import java.net.URI

/**
 * Resolves AWS Secrets Manager references in the form `aws-secretsmanager:secret-id[#field]`.
 *
 * If the secret payload is plain text, it is returned directly. If it is JSON,
 * callers may select a field explicitly with `#field`, or fall back to the configured
 * `defaultField` when present.
 */
class AwsSecretsManagerSecretValueResolver(
    private val client: AwsSecretsManagerLookupClient,
    private val defaultField: String = "value",
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : SecretValueResolver, SpringBuiltInSecretValueResolver {
    init {
        require(defaultField.isNotBlank()) { "AWS Secrets Manager defaultField must not be blank" }
    }

    override fun resolve(secretRef: String): String? {
        val reference = parse(secretRef) ?: return null
        val secretValue = client.secretValue(reference.secretId) ?: return null
        return extractValue(secretValue, reference.field)
    }

    private fun extractValue(secretValue: String, explicitField: String?): String? {
        val trimmed = secretValue.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val parsed = runCatching { objectMapper.readTree(trimmed) }.getOrNull()
        if (explicitField != null) {
            return parsed?.path(explicitField)
                ?.takeIf { !it.isMissingNode && !it.isNull && it.isValueNode }
                ?.asText()
                ?.takeIf { it.isNotBlank() }
        }

        if (parsed == null || parsed.isValueNode) {
            val value = parsed?.asText() ?: trimmed
            return value.takeIf { it.isNotBlank() }
        }

        parsed.path(defaultField)
            .takeIf { !it.isMissingNode && !it.isNull && it.isValueNode }
            ?.asText()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        if (parsed.isObject && parsed.size() == 1) {
            val onlyEntry = parsed.fields().next()
            if (onlyEntry.value.isValueNode) {
                return onlyEntry.value.asText().takeIf { it.isNotBlank() }
            }
        }

        return null
    }

    private data class AwsReference(
        val secretId: String,
        val field: String?,
    )

    companion object {
        @JvmStatic
        fun fromSdk(
            region: String,
            endpoint: String? = null,
            accessKeyId: String? = null,
            secretAccessKey: String? = null,
            sessionToken: String? = null,
            defaultField: String = "value",
            objectMapper: ObjectMapper = ObjectMapper(),
        ): AwsSecretsManagerSecretValueResolver {
            require(region.isNotBlank()) { "AWS Secrets Manager region must not be blank" }
            val builder = SecretsManagerClient.builder()
                .region(Region.of(region))

            endpoint?.takeIf { it.isNotBlank() }?.let { builder.endpointOverride(URI.create(it)) }

            when {
                !accessKeyId.isNullOrBlank() && !secretAccessKey.isNullOrBlank() -> {
                    val credentials = if (!sessionToken.isNullOrBlank()) {
                        AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)
                    } else {
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                    }
                    builder.credentialsProvider(StaticCredentialsProvider.create(credentials))
                }
                accessKeyId != null || secretAccessKey != null || sessionToken != null -> {
                    throw IllegalArgumentException(
                        "AWS Secrets Manager accessKeyId and secretAccessKey must be configured together when using static credentials",
                    )
                }
                else -> builder.credentialsProvider(DefaultCredentialsProvider.builder().build())
            }

            return AwsSecretsManagerSecretValueResolver(
                client = SdkAwsSecretsManagerLookupClient(builder.build()),
                defaultField = defaultField,
                objectMapper = objectMapper,
            )
        }

        private fun parse(secretRef: String): AwsReference? {
            if (!secretRef.startsWith("aws-secretsmanager:")) {
                return null
            }

            val rawReference = secretRef.removePrefix("aws-secretsmanager:")
            if (rawReference.isBlank()) {
                return null
            }

            val fieldSeparator = rawReference.indexOf('#')
            val secretId = if (fieldSeparator >= 0) rawReference.substring(0, fieldSeparator) else rawReference
            val field = if (fieldSeparator >= 0) rawReference.substring(fieldSeparator + 1).takeIf { it.isNotBlank() } else null
            return secretId.takeIf { it.isNotBlank() }?.let { AwsReference(secretId = it, field = field) }
        }
    }
}

fun interface AwsSecretsManagerLookupClient {
    fun secretValue(secretId: String): String?
}

private class SdkAwsSecretsManagerLookupClient(
    private val client: SecretsManagerClient,
) : AwsSecretsManagerLookupClient {
    override fun secretValue(secretId: String): String? {
        try {
            val response = client.getSecretValue(
                GetSecretValueRequest.builder()
                    .secretId(secretId)
                    .build(),
            )
            return response.secretString()
                ?: response.secretBinary()?.asUtf8String()
        } catch (_: ResourceNotFoundException) {
            return null
        } catch (error: SdkException) {
            throw IllegalStateException("AWS Secrets Manager lookup failed for '$secretId'", error)
        }
    }
}
