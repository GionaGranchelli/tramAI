package dev.tramai.persistence.file

import java.nio.file.Path

/**
 * Configuration for a single file-backed persistent store instance.
 *
 * @property rootDirectory Base directory under which all store files are placed.
 * @property encryption Active encryption configuration.
 * @property verifyOnOpen If true, re-verifies envelope integrity on every open (default: true).
 */
data class FileBackedStoreConfiguration(
    val rootDirectory: Path,
    val encryption: FileStoreEncryptionConfiguration,
    val verifyOnOpen: Boolean = true,
)
