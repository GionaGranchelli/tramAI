package dev.tramai.spring

import dev.tramai.core.secret.SecretValueResolver

/**
 * Internal composition chain for the bootstrap secret resolution set
 * (user + environment + bootstrap markers).
 *
 * The bootstrap chain is deliberately NOT exposed as a raw
 * [SecretValueResolver] bean: it is an internal assembly detail and must not
 * participate in unqualified application-level [SecretValueResolver]
 * injection. Modules that need it (Vault/AWS credential bootstrapping) inject
 * this holder by type.
 */
class SpringBootstrapSecretChain(
    val resolver: SecretValueResolver,
)
