package dev.tramai.persistence.file

/**
 * Root exception for all tramai-persistence-file errors.
 *
 * **Security invariant:** Messages contain safe reason codes only.
 * NEVER embed raw record IDs, workflow IDs, tool arguments, keys,
 * ciphertext, or nonces in exception messages.
 */
sealed class FileStoreException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Raised when store configuration is invalid or cannot be loaded.
 */
class FileStoreConfigurationException(msg: String, cause: Throwable? = null) :
    FileStoreException(msg, cause)

/**
 * Raised when a file lock cannot be acquired within the timeout.
 */
class FileStoreLockUnavailableException(msg: String) :
    FileStoreException(msg)

/**
 * Raised on permission / access-control failures.
 */
class FileStorePermissionException(msg: String) :
    FileStoreException(msg)

/**
 * Raised when stored data fails integrity verification
 * (wrong key, mutated ciphertext, bad nonce, AAD mismatch,
 * GCM tag validation failure).
 */
class FileStoreCorruptionException(msg: String, cause: Throwable? = null) :
    FileStoreException(msg, cause)

/**
 * Raised when an on-disk format version is not supported.
 */
class FileStoreUnsupportedFormatException(msg: String) :
    FileStoreException(msg)
