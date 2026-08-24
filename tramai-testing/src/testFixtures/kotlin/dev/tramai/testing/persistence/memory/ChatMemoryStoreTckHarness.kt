package dev.tramai.testing.persistence.memory

import dev.tramai.core.memory.ChatMemoryStore

/**
 * Epic 8.1h: per-test backend harness for the
 * [dev.tramai.core.memory.ChatMemoryStore] compatibility contract.
 *
 * [primary] and [peer] are DISTINCT store objects over the SAME physical
 * backend. A single in-memory mutex inside one store object is not evidence
 * for JDBC/Redis shared by multiple application nodes — the concurrency
 * contract must prove coordination at the backend level.
 */
interface ChatMemoryStoreTckHarness : AutoCloseable {

    /** First store instance. */
    val primary: ChatMemoryStore

    /** Second store instance over the same physical backend. */
    val peer: ChatMemoryStore

    override fun close() {}
}
