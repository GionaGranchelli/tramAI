package dev.tramai.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * AWS Secrets Manager-backed secret resolution settings
 * (`tramai.secrets.aws-secrets-manager.*`).
 *
 * Owned by the AWS secrets module — the Spring core never binds or reads
 * secret-backend-specific configuration.
 */
@ConfigurationProperties("tramai.secrets.aws-secrets-manager")
data class AwsSecretsManagerProperties(
    var enabled: Boolean = false,
    var region: String? = null,
    var endpoint: String? = null,
    var accessKeyId: String? = null,
    var accessKeyIdSecretRef: String? = null,
    var secretAccessKey: String? = null,
    var secretAccessKeySecretRef: String? = null,
    var sessionToken: String? = null,
    var sessionTokenSecretRef: String? = null,
    var defaultField: String = "value",
)
