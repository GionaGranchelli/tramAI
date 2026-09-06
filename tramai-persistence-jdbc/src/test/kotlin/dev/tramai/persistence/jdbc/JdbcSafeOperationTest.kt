package dev.tramai.persistence.jdbc

import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.StructuredOutputException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertSame

class JdbcSafeOperationTest {
    @Test
    fun `withSafeJdbc returns result on success`() {
        val result = withSafeJdbc({ "diagnostic" }) { "hello" }
        assertEquals("hello", result)
    }

    @Test
    fun `withSafeJdbc maps SQLException to IllegalStateException with lazy diagnostic`() {
        var evaluated = false
        val exception =
            assertThrows<IllegalStateException> {
                withSafeJdbc({
                    evaluated = true
                    "Sanitized database error"
                }) {
                    throw SQLException("syntax error at or near 'SELECT * FROM users_secret'")
                }
            }
        assertEquals("Sanitized database error", exception.message)
        assertEquals(true, evaluated)
    }

    @Test
    fun `withSafeJdbc rethrows CancellationException unmodified`() {
        val cancellation = CancellationException("task cancelled")
        val thrown =
            assertThrows<CancellationException> {
                withSafeJdbc({ "diagnostic" }) {
                    throw cancellation
                }
            }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `withSafeJdbc rethrows domain exceptions unmodified`() {
        val approvalException = ApprovalStoreNotFoundException("approval-123")
        val thrownApproval =
            assertThrows<ApprovalStoreNotFoundException> {
                withSafeJdbc({ "diagnostic" }) {
                    throw approvalException
                }
            }
        assertSame(approvalException, thrownApproval)

        val tramaiException = StructuredOutputException("tramai failed")
        val thrownTramai =
            assertThrows<StructuredOutputException> {
                withSafeJdbc({ "diagnostic" }) {
                    throw tramaiException
                }
            }
        assertSame(tramaiException, thrownTramai)

        val illegalArg = IllegalArgumentException("bad arg")
        val thrownArg =
            assertThrows<IllegalArgumentException> {
                withSafeJdbc({ "diagnostic" }) {
                    throw illegalArg
                }
            }
        assertSame(illegalArg, thrownArg)
    }

    @Test
    fun `withSafeJdbc propagates fatal JVM Errors without catching or wrapping`() {
        val assertionError = AssertionError("assertion failed")
        val thrownAssertion =
            assertThrows<AssertionError> {
                withSafeJdbc({ "diagnostic" }) {
                    throw assertionError
                }
            }
        assertSame(assertionError, thrownAssertion)

        val oom = OutOfMemoryError("out of memory")
        val thrownOom =
            assertThrows<OutOfMemoryError> {
                withSafeJdbc({ "diagnostic" }) {
                    throw oom
                }
            }
        assertSame(oom, thrownOom)
    }
}
