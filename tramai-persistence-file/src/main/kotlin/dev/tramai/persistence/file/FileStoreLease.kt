package dev.tramai.persistence.file

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Shared operation-scoped lease for file-backed stores.
 *
 * Uses a [ReentrantReadWriteLock] so that:
 * - Multiple store operations can run concurrently (read lock)
 * - [close] waits for all in-flight operations to drain (write lock)
 * - After close, [requireOpen] permanently rejects new operations
 *
 * Every public store method must wrap its body in [withOpenOperation]:
 * ```kotlin
 * lease.withOpenOperation {
 *     // entire public method
 * }
 * ```
 */
internal class FileStoreLease {
    private val rwLock = ReentrantReadWriteLock()
    private var closed = false

    /**
     * Executes [block] under the lease guard.
     *
     * Acquires the read lock, checks that the lease is not closed,
     * executes [block], and releases the read lock in `finally`.
     *
     * @throws IllegalStateException if the lease is closed.
     */
    inline fun <T> withOpenOperation(block: () -> T): T {
        rwLock.readLock().lock()
        try {
            check(!closed) { "file-store-closed" }
            return block()
        } finally {
            rwLock.readLock().unlock()
        }
    }

    /**
     * Closes the lease. Acquires the **write lock**, which blocks
     * until all current read-lease operations finish, then marks
     * the lease as closed and releases the write lock.
     *
     * After this call, any subsequent [withOpenOperation] will
     * immediately throw [IllegalStateException].
     */
    fun close() {
        rwLock.writeLock().lock()
        try {
            closed = true
        } finally {
            rwLock.writeLock().unlock()
        }
    }
}
