package dev.tramai.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Vault-backed secret resolution settings (`tramai.secrets.vault.*`).
 *
 * Owned by the Vault secrets module — the Spring core never binds or reads
 * secret-backend-specific configuration.
 */
@ConfigurationProperties("tramai.secrets.vault")
data class VaultSecretProperties(
    var enabled: Boolean = false,
    var baseUrl: String? = null,
    var token: String? = null,
    var tokenSecretRef: String? = null,
    var namespace: String? = null,
    var mountPath: String = "secret",
    var kvVersion: Int = 2,
    var defaultField: String = "value",
)
