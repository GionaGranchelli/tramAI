package dev.tramai.spring

import dev.tramai.core.secret.SecretValueResolver

/**
 * Marker for secret resolvers contributed by optional secret modules
 * (Vault, AWS Secrets Manager).
 *
 * The Spring core uses this marker to distinguish built-in secret resolvers
 * from user-supplied [SecretValueResolver] beans when assembling the
 * resolution chain, so bootstrap and full-resolution chains keep the exact
 * documented ordering. Core still knows nothing about Vault or AWS
 * specifically — it only knows that marked resolvers are built-ins.
 */
interface SpringBuiltInSecretValueResolver : SecretValueResolver
