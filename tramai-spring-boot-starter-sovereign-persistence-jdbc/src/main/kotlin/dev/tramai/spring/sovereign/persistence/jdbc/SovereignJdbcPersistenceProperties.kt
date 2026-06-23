package dev.tramai.spring.sovereign.persistence.jdbc

import java.nio.file.Path
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Externalized configuration for TramAI sovereign JDBC persistence.
 *
 * Usage:
 * ```yaml
 * tramai:
 *   sovereign:
 *     persistence:
 *       type: jdbc
 *       jdbc:
 *         claim-lease-duration: 5m
 *         max-claim-limit: 500
 *       encryption:
 *         key-env: TRAMAI_SOVEREIGN_STORE_KEY
 * ```
 *
 * ## Key requirements
 * - Exactly one key source must be specified: `key-env` or `key-file` (not both, not neither)
 * - Key must be base64-encoded 256-bit AES key (decodes to 32 bytes)
 * - Plaintext keys in YAML are **not** supported
 * - Keys are never logged and never appear in exception messages
 */
@ConfigurationProperties(prefix = "tramai.sovereign.persistence")
data class SovereignJdbcPersistenceProperties(
    /** Persistence type: "memory" (default), "file", or "jdbc". */
    var type: String = "memory",

    /** JDBC-specific configuration for sovereign stores. */
    var jdbc: Jdbc = Jdbc(),

    /** Encryption configuration for JDBC stores. */
    var encryption: Encryption = Encryption(),
) {
    data class Jdbc(
        /** Duration after which an EMITTING outbox claim expires (default 5 minutes). */
        var claimLeaseDuration: Duration = Duration.ofMinutes(5),

        /** Maximum records claimed in one [JdbcSovereignOpsAuditOutboxStore.claimPending] call (default 500). */
        var maxClaimLimit: Int = 500,
    )

    data class Encryption(
        /** Name of the environment variable containing the base64-encoded 256-bit AES key. */
        var keyEnv: String? = null,

        /** Path to a file containing the base64-encoded 256-bit AES key. */
        var keyFile: Path? = null,

        /** Identifier for the encryption key (stored in `encryption_key_id` columns). */
        var keyId: String = "default",
    )
}
