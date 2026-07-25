package dev.tramai.core.coroutines

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Contract tests for [rethrowIfCancellation] — the shared cancellation rethrow helper.
 *
 * These tests prove that:
 * - The same [CancellationException] instance is rethrown (preserving identity).
 * - Normal exceptions pass through without modification.
 */
class CancellationContractTest {

    @Test
    fun `rethrowIfCancellation rethrows same CancellationException instance`() {
        val cancellation = CancellationException("test cancellation")
        val thrown = assertFailsWith<CancellationException> {
            cancellation.rethrowIfCancellation()
        }
        assertSame(cancellation, thrown, "Must rethrow the exact same CancellationException instance")
    }

    @Test
    fun `rethrowIfCancellation rethrows CancellationException subclass`() {
        val custom = object : CancellationException("custom") {}
        val thrown = assertFailsWith<CancellationException> {
            custom.rethrowIfCancellation()
        }
        assertSame(custom, thrown, "Must rethrow the same CancellationException subclass instance")
    }

    @Test
    fun `rethrowIfCancellation is a no-op for RuntimeException`() {
        val error = RuntimeException("normal failure")
        // Must not throw — this is the no-op contract for non-cancellation exceptions
        error.rethrowIfCancellation()
    }

    @Test
    fun `rethrowIfCancellation is a no-op for Exception`() {
        val error = Exception("normal failure")
        error.rethrowIfCancellation()
    }

    @Test
    fun `rethrowIfCancellation is a no-op for Throwable`() {
        val error = Throwable("normal failure")
        error.rethrowIfCancellation()
    }

    @Test
    fun `rethrowIfCancellation is a no-op for IOException`() {
        val error = java.io.IOException("IO failure")
        error.rethrowIfCancellation()
    }

    @Test
    fun `rethrowIfCancellation is a no-op for arbitrary custom exception`() {
        class CustomException(message: String) : Exception(message)
        val error = CustomException("custom")
        error.rethrowIfCancellation()
    }
}
