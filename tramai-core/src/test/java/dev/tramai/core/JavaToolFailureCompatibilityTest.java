package dev.tramai.core;

import dev.tramai.core.exception.ToolInvalidInputException;
import dev.tramai.core.model.ModelVisibleToolMessage;
import dev.tramai.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java-visible compatibility test for the stable tramai-core tool contracts.
 *
 * Proves that the 0.5.0 JVM signatures remain callable from Java after the
 * 0.6.0 safe-failure additions: constructors, getters, and static factories
 * are exercised exactly as a pre-existing compiled Java client would use
 * them. Compilation of this file is the assertion — if a member is removed
 * or mangled, this test stops compiling.
 */
class JavaToolFailureCompatibilityTest {

    @Test
    void legacyToolResultShapesRemainCallable() {
        // 0.5.0 signatures: single-String constructors + message getters.
        ToolResult.InvalidInput invalidInput = new ToolResult.InvalidInput("bad input");
        assertEquals("bad input", invalidInput.getMessage());

        ToolResult.PermanentFailure permanentFailure = new ToolResult.PermanentFailure("boom");
        assertEquals("boom", permanentFailure.getMessage());

        ToolResult.TransientFailure transientFailure =
            new ToolResult.TransientFailure(new RuntimeException("retry me"));
        assertNotNull(transientFailure.getCause());

        ToolResult.Success success = new ToolResult.Success("ok", null);
        assertEquals("ok", success.getValue());
    }

    @Test
    void toolInvalidInputExceptionStringConstructorRemainsCallable() {
        ToolInvalidInputException plain = new ToolInvalidInputException("diagnostic only");
        assertEquals("diagnostic only", plain.getMessage());
        // Diagnostic-only by default: no safe model message.
        assertEquals(null, plain.getSafeModelMessage());

        ToolInvalidInputException safe =
            ToolInvalidInputException.withSafeModelMessage("diagnostic", "model-visible");
        assertEquals("model-visible", safe.getSafeModelMessage().getValue());
    }

    @Test
    void modelVisibleToolMessageFactoryIsPlainlyCallable() {
        // @JvmStatic factory: no name mangling, takes an ordinary String.
        ModelVisibleToolMessage message = ModelVisibleToolMessage.trusted("rejected");
        assertEquals("rejected", message.getValue());

        assertThrows(IllegalArgumentException.class,
            () -> ModelVisibleToolMessage.trusted("line one\nline two"));
    }

    @Test
    void modelVisibleToolMessageHasNoCopyAndNoAccessibleConstructor() throws Exception {
        assertThrows(NoSuchMethodException.class,
            () -> ModelVisibleToolMessage.class.getDeclaredMethod("copy", String.class));

        Constructor<ModelVisibleToolMessage> constructor =
            ModelVisibleToolMessage.class.getDeclaredConstructor(String.class);
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
}
