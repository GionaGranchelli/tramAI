package dev.tramai.spring.sovereign.persistence.file

import java.nio.file.Path
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Externalized configuration for TramAI sovereign file-backed persistence.
 *
 * Usage:
 * ```yaml
 * tramai:
 *   sovereign:
 *     persistence:
 *       type: file
 *       base-dir: ./data/tramai-sovereign
 *       encryption:
 *         key-env: TRAMAI_SOVEREIGN_STORE_KEY
 * ```
 */
@ConfigurationProperties(prefix = "tramai.sovereign.persistence")
data class SovereignFilePersistenceProperties(
    /** Persistence type: "memory" (default) or "file". */
    var type: String = "memory",

    /** Base directory for file-backed sovereign stores. Required when type=file. */
    var baseDir: Path? = null,

    /** Encryption configuration for file-backed stores. */
    var encryption: Encryption = Encryption(),
) {
    data class Encryption(
        /** Name of the environment variable containing the base64-encoded 256-bit AES key. */
        var keyEnv: String? = null,

        /** Path to a file containing the base64-encoded 256-bit AES key. */
        var keyFile: Path? = null,
    )
}
