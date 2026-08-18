package dev.tramai.engine.invocation

import dev.tramai.engine.approval.ClaimedResumeExecutor
import dev.tramai.engine.provider.ProviderExecutionCoordinator
import dev.tramai.engine.streaming.StreamingExecutionCoordinator
import dev.tramai.engine.structured.StructuredResponseCoordinator
import dev.tramai.engine.tool.ToolInvocationExecutor
import dev.tramai.engine.tool.ToolReinjectionCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler

/**
 * Architectural assertions for Epic 3.7: the JVM proxy adapter must be a thin
 * adapter, not a god-object. These tests fail if the handler regresses into
 * owning execution algorithms or approval-resume execution.
 */
class TramaiInvocationHandlerArchitectureTest {

    @Test
    fun `handler is a pure JVM proxy adapter - not an approval resume executor`() {
        assertThat(InvocationHandler::class.java.isAssignableFrom(TramaiInvocationHandler::class.java))
            .describedAs("handler must implement the JVM proxy InvocationHandler")
            .isTrue()
        assertThat(ClaimedResumeExecutor::class.java.isAssignableFrom(TramaiInvocationHandler::class.java))
            .describedAs("handler must NOT implement ClaimedResumeExecutor - approval resume lives in the execution layer")
            .isFalse()
    }

    @Test
    fun `handler owns no execution algorithms`() {
        val methods = TramaiInvocationHandler::class.java.declaredMethods.map { it.name }.toSet()
        val fieldTypes = TramaiInvocationHandler::class.java.declaredFields.map { it.type }.toSet()

        // Top-level execution algorithms must live in coordinators, not the handler.
        listOf("executeRaw", "executeWithTools", "sanitizeProviderResponse", "applyProviderOutputDlp").forEach { algorithm ->
            assertThat(methods).describedAs("handler must not declare $algorithm").doesNotContain(algorithm)
        }
        // The handler must not hold execution coordinators as collaborators.
        // Compare the actual Class objects, not name strings, so a renamed or
        // qualified declaration cannot silently pass.
        listOf(
            ProviderExecutionCoordinator::class.java,
            ToolInvocationExecutor::class.java,
            StructuredResponseCoordinator::class.java,
            StreamingExecutionCoordinator::class.java,
            ToolReinjectionCoordinator::class.java,
            ToolLoopCoordinator::class.java,
            RawResponseCoordinator::class.java,
        ).forEach { coordinator ->
            assertThat(fieldTypes).describedAs("handler must not hold $coordinator").doesNotContain(coordinator)
        }
    }

    @Test
    fun `handler has no policy or DLP enforcement methods`() {
        val methods = TramaiInvocationHandler::class.java.declaredMethods.map { it.name }.toSet()
        listOf("enforceBeforeProviderResolution", "enforceBeforeProviderInvocation", "enforceFallbackTransition", "enforceBeforeResponseReturn")
            .forEach { enforcement ->
                assertThat(methods).describedAs("handler must not declare $enforcement").doesNotContain(enforcement)
            }
    }
}
