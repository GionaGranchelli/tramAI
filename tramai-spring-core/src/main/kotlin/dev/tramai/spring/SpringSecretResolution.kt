package dev.tramai.spring

import dev.tramai.core.secret.SecretValueResolver

/**
 * Shared secret-resolution rule used by the Spring core and by provider
 * adapter auto-configurations.
 *
 * The mutual-exclusion guard (direct value vs secret reference) and the
 * failure message shape are part of the observable configuration contract:
 * they are exercised by the Epic 6.3 characterization suite and must be
 * byte-identical across modules.
 */
object SpringSecretResolution {

    fun resolve(
        directValue: String?,
        secretRef: String?,
        fieldName: String,
        secretResolver: SecretValueResolver,
    ): String? {
        val trimmedDirect = directValue?.trim()?.takeIf { it.isNotBlank() }
        val trimmedRef = secretRef?.trim()?.takeIf { it.isNotBlank() }
        check(trimmedDirect == null || trimmedRef == null) {
            "$fieldName cannot be configured together with its secret reference"
        }
        if (trimmedRef == null) {
            return trimmedDirect
        }

        return secretResolver.resolve(trimmedRef)
            ?: throw IllegalStateException("No SecretValueResolver could resolve '$trimmedRef' for $fieldName")
    }
}
