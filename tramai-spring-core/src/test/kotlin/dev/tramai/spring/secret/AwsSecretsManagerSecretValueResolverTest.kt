package dev.tramai.spring.secret

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class AwsSecretsManagerSecretValueResolverTest {
    @Test
    fun `returns plain secret strings directly`() {
        val resolver = AwsSecretsManagerSecretValueResolver(
            client = AwsSecretsManagerLookupClient { "plain-secret" },
        )

        val resolved = resolver.resolve("aws-secretsmanager:prod/openai/api-key")

        assertThat(resolved).isEqualTo("plain-secret")
    }

    @Test
    fun `returns the default field from json secret strings`() {
        val resolver = AwsSecretsManagerSecretValueResolver(
            client = AwsSecretsManagerLookupClient { """{"value":"json-secret","secondary":"unused"}""" },
        )

        val resolved = resolver.resolve("aws-secretsmanager:prod/openai/api-key")

        assertThat(resolved).isEqualTo("json-secret")
    }

    @Test
    fun `returns an explicit json field from the secret reference`() {
        val resolver = AwsSecretsManagerSecretValueResolver(
            client = AwsSecretsManagerLookupClient { """{"apiKey":"json-secret"}""" },
        )

        val resolved = resolver.resolve("aws-secretsmanager:prod/openai/api-key#apiKey")

        assertThat(resolved).isEqualTo("json-secret")
    }
}
