package dev.tramai.spring

import dev.tramai.spring.secret.AwsSecretsManagerSecretValueResolver
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Contributes the AWS Secrets Manager resolver to the full chain.
 *
 * AWS credentials resolve through the bootstrap chain (user + environment +
 * file), which is why this module depends on the bootstrap resolver rather
 * than the full chain — otherwise AWS would try to resolve its own
 * credentials through itself.
 */
@AutoConfiguration(before = [TramaiSecretResolutionAutoConfiguration::class])
@EnableConfigurationProperties(AwsSecretsManagerProperties::class)
@ConditionalOnMissingBean(dev.tramai.standalone.Tramai::class)
class AwsSecretsManagerSecretValueResolverAutoConfiguration {

    @Bean
    fun awsSecretsManagerSecretValueResolver(
        properties: AwsSecretsManagerProperties,
        bootstrapSecretChain: SpringBootstrapSecretChain,
    ): SpringBuiltInSecretValueResolver? {
        val aws = properties
        if (!aws.enabled) {
            return null
        }

        val region = aws.region?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "tramai.secrets.aws-secrets-manager.region must be configured when AWS Secrets Manager resolution is enabled",
            )
        val accessKeyId = SpringSecretResolution.resolve(
            directValue = aws.accessKeyId,
            secretRef = aws.accessKeyIdSecretRef,
            fieldName = "tramai.secrets.aws-secrets-manager.accessKeyId",
            secretResolver = bootstrapSecretChain.resolver,
        )
        val secretAccessKey = SpringSecretResolution.resolve(
            directValue = aws.secretAccessKey,
            secretRef = aws.secretAccessKeySecretRef,
            fieldName = "tramai.secrets.aws-secrets-manager.secretAccessKey",
            secretResolver = bootstrapSecretChain.resolver,
        )
        val sessionToken = SpringSecretResolution.resolve(
            directValue = aws.sessionToken,
            secretRef = aws.sessionTokenSecretRef,
            fieldName = "tramai.secrets.aws-secrets-manager.sessionToken",
            secretResolver = bootstrapSecretChain.resolver,
        )

        return AwsSecretsManagerSecretValueResolver.fromSdk(
            region = region,
            endpoint = aws.endpoint,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = sessionToken,
            defaultField = aws.defaultField,
        )
    }
}
