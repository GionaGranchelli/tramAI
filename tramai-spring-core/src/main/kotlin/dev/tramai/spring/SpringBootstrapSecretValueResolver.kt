package dev.tramai.spring

import dev.tramai.core.secret.SecretValueResolver

/**
 * Marker for secret resolvers contributed by optional secret modules that
 * are eligible for the BOOTSTRAP chain (e.g. file-based secrets).
 *
 * The bootstrap chain (user + environment + these) is what Vault/AWS
 * credentials resolve through. Resolvers that need bootstrap themselves
 * (Vault, AWS) must NOT implement this marker — they implement
 * [SpringBuiltInSecretValueResolver] instead, which is excluded from
 * bootstrap.
 */
interface SpringBootstrapSecretValueResolver : SecretValueResolver
