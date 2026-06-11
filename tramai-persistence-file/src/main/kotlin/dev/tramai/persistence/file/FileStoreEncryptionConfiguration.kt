package dev.tramai.persistence.file

import javax.crypto.SecretKey

/**
 * Resolves a [SecretKey] for a given key identifier.
 * Implementations must be thread-safe and idempotent.
 */
fun interface FileStoreEncryptionKeyProvider {
    fun resolve(keyId: String): SecretKey
}

/**
 * Active encryption configuration for a file-backed store.
 *
 * @property activeKeyId Identifies the key used for new writes.
 * @property keyProvider Resolver for all keys by ID (including the active key).
 */
data class FileStoreEncryptionConfiguration(
    val activeKeyId: String,
    val keyProvider: FileStoreEncryptionKeyProvider,
)
