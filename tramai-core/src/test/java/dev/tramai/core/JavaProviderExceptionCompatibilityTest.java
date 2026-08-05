package dev.tramai.core;

import dev.tramai.core.exception.ProviderException;
import dev.tramai.core.exception.ProviderFailureCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java-visible compatibility test for the stable ProviderException contract.
 *
 * Proves that the pre-0.6.0 JVM constructor and getter signatures remain
 * callable from Java after the safe provider-failure additions, and that the
 * typed [failureCode] path is usable. Compilation of this file is the
 * assertion — if a member is removed or mangled, this test stops compiling.
 */
class JavaProviderExceptionCompatibilityTest {

    @Test
    void legacyConstructorAndGettersRemainCallable() {
        // Pre-0.6.0 signature: (String, Throwable, Integer, boolean, Long).
        ProviderException legacy = new ProviderException("legacy message", null, 429, true, 2000L);

        assertEquals("legacy message", legacy.getMessage());
        assertNull(legacy.getCause());
        assertEquals(Integer.valueOf(429), legacy.getStatusCode());
        assertTrue(legacy.getRetryable());
        assertEquals(Long.valueOf(2000L), legacy.getRetryAfterMillis());
        assertNull(legacy.getFailureCode());
    }

    @Test
    void typedFailureCodeConstructorIsCallable() {
        ProviderException typed = new ProviderException(
            "safe message", null, 500, true, null, ProviderFailureCode.HTTP_REJECTED);

        assertEquals(ProviderFailureCode.HTTP_REJECTED, typed.getFailureCode());
        assertEquals(Integer.valueOf(500), typed.getStatusCode());
        assertTrue(typed.getRetryable());
    }
}
