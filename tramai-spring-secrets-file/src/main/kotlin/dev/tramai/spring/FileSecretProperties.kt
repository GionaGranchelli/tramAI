package dev.tramai.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Local file secret resolution settings (`tramai.secrets.file.*`).
 *
 * Owned by the file secrets module — the Spring core never binds or reads
 * secret-backend-specific configuration.
 */
@ConfigurationProperties("tramai.secrets.file")
data class FileSecretProperties(
    var allowedDirectory: String? = null,
)
