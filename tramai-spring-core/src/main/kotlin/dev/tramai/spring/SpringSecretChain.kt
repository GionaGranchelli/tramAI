package dev.tramai.spring

import dev.tramai.core.secret.SecretValueResolver

/**
 * Internal full secret resolution chain (user + built-ins + environment +
 * bootstrap markers).
 *
 * The full chain is deliberately NOT exposed as a raw [SecretValueResolver]
 * bean: it is an internal assembly detail and must not participate in
 * unqualified application-level [SecretValueResolver] injection. Provider
 * adapter modules inject this holder by type to resolve secret references in
 * their property models.
 */
class SpringSecretChain(
    val resolver: SecretValueResolver,
)
