package dev.tramai.spring

import dev.tramai.core.secret.FileSecretValueResolver
import dev.tramai.core.secret.SecretValueResolver
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.nio.file.Path

/**
 * Contributes the file-based secret resolver to the bootstrap chain.
 *
 * Wraps core's [FileSecretValueResolver] in the bootstrap marker so it is
 * included in the bootstrap chain (and therefore usable by Vault/AWS
 * credentials) but stays out of the user-resolver set.
 */
@AutoConfiguration(before = [TramaiSecretResolutionAutoConfiguration::class])
@EnableConfigurationProperties(FileSecretProperties::class)
@ConditionalOnMissingBean(dev.tramai.standalone.Tramai::class)
class FileSecretValueResolverAutoConfiguration {

    @Bean
    fun springFileSecretValueResolver(properties: FileSecretProperties): SpringBootstrapSecretValueResolver =
        SpringFileSecretValueResolver(
            allowedDirectory = properties.allowedDirectory
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of),
        )

    /**
     * Marker wrapper so core's FileSecretValueResolver (which lives in
     * tramai-core and cannot depend on the Spring marker) participates in
     * the bootstrap chain.
     */
    private class SpringFileSecretValueResolver(
        allowedDirectory: Path?,
    ) : SpringBootstrapSecretValueResolver,
        SecretValueResolver by FileSecretValueResolver(allowedDirectory)
}
