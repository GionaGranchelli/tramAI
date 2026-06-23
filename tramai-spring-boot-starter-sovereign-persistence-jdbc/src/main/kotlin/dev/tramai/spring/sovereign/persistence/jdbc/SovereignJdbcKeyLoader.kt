package dev.tramai.spring.sovereign.persistence.jdbc

import java.io.IOException
import java.nio.file.Files
import java.util.Base64

/**
 * Loads and validates a 256-bit AES encryption key from environment variable or file.
 *
 * Supports two mutually exclusive sources:
 * - `key-env`: reads a base64-encoded 256-bit key from an environment variable
 * - `key-file`: reads a base64-encoded 256-bit key from a file on disk
 *
 * Validation rules:
 * - Exactly one key source must be specified (not zero, not both)
 * - The env var must exist and be non-blank
 * - The key file must exist and be readable
 * - The key must be valid base64 and decode to exactly 32 bytes
 *
 * Keys are never logged and never included in exception messages.
 * Plaintext keys in YAML are not supported — always use env vars or files.
 */
object SovereignJdbcKeyLoader {

    /**
     * Loads and validates the AES encryption key from the configured source.
     *
     * @param properties The JDBC persistence properties.
     * @return A 32-byte AES-256 key.
     * @throws IllegalStateException with a fail-closed error code on any validation failure.
     */
    fun load(properties: SovereignJdbcPersistenceProperties): ByteArray {
        val enc = properties.encryption
        val keyEnv = enc.keyEnv
        val keyFile = enc.keyFile

        // ── Exactly one key source ──
        when {
            keyEnv == null && keyFile == null -> {
                throw IllegalStateException(
                    "tramai-sovereign-jdbc-persistence-missing-key-source",
                )
            }
            keyEnv != null && keyFile != null -> {
                throw IllegalStateException(
                    "tramai-sovereign-jdbc-persistence-ambiguous-key-source",
                )
            }
        }

        // ── Load base64 key from the configured source ──
        val base64Key: String = when {
            keyEnv != null -> {
                val value = System.getenv(keyEnv)
                check(!value.isNullOrBlank()) {
                    "tramai-sovereign-jdbc-persistence-missing-key-env"
                }
                value.trim()
            }
            keyFile != null -> {
                check(Files.exists(keyFile)) {
                    "tramai-sovereign-jdbc-persistence-key-file-missing"
                }
                try {
                    keyFile.toFile().readText().trim()
                } catch (e: IOException) {
                    throw IllegalStateException(
                        "tramai-sovereign-jdbc-persistence-key-file-missing",
                    )
                }
            }
            else -> error("unreachable")
        }

        // ── Base64 decode ──
        val rawKey: ByteArray
        try {
            rawKey = Base64.getDecoder().decode(base64Key)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "tramai-sovereign-jdbc-persistence-invalid-key",
            )
        }

        // ── Must be exactly 32 bytes (256 bits) ──
        check(rawKey.size == 32) {
            "tramai-sovereign-jdbc-persistence-invalid-key"
        }

        return rawKey
    }
}
