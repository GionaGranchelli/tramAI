@file:Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")

package dev.tramai.persistence.jdbc

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ApprovalContinuationStoreException
import dev.tramai.core.exception.ApprovalStoreException
import dev.tramai.core.exception.TramaiException
import java.sql.SQLException
import java.util.concurrent.CancellationException

/**
 * Executes a JDBC store block and ensures unexpected database errors / SQL exceptions
 * do not leak raw SQL queries, table structures, connection strings, or database vendor
 * diagnostics up the stack.
 *
 * Coroutine cancellation and domain-specific exceptions are preserved unchanged.
 */
internal inline fun <T> withSafeJdbc(
    lazyDiagnosticMessage: () -> String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) {
            throw e
        }
        e.rethrowIfCancellation()
        when (e) {
            is ApprovalStoreException,
            is ApprovalContinuationStoreException,
            is TramaiException,
            is IllegalArgumentException,
            -> throw e

            is SQLException -> throw IllegalStateException(lazyDiagnosticMessage())

            is IllegalStateException -> throw e

            else -> throw IllegalStateException(lazyDiagnosticMessage())
        }
    }
