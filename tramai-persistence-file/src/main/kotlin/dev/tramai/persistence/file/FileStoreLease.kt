package dev.tramai.persistence.file

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared close-state lease for file-backed stores.
 *
 * All store operations must call [requireOpen] at their entry point.
 * After [close] the lease is permanently closed and all stores
 * sharing it will reject further operations.
 */
internal class FileStoreLease {
    private val closed = AtomicBoolean(false)

    fun requireOpen() {
        check(!closed.get()) { "file-store-closed" }
    }

    fun close() {
        closed.set(true)
    }
}
