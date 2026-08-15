package dev.tramai.engine.planning

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.engine.ToolRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OperationExecutionPlanTest {

    @Test
    fun `wraps compiled definition and method identity`() {
        val method = PlanService::class.java.methods.single { it.name == "answer" }
        val plan = OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory())
            .compile(PlanService::class.java, method, null)

        assertThat(plan.definition.method).isSameAs(method)
        assertThat(plan.fingerprint).matches("[a-f0-9]{64}")
        assertThat(plan.serviceInterface).isEqualTo(PlanService::class.java.name)
        assertThat(plan.methodName).isEqualTo("answer")
    }

    @Test
    fun `copy preserves immutable plan values and definition identity`() {
        val method = PlanService::class.java.methods.single { it.name == "answer" }
        val plan = OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory())
            .compile(PlanService::class.java, method, null)

        val copy = plan.copy()

        assertThat(copy).isEqualTo(plan)
        assertThat(copy.definition).isSameAs(plan.definition)
    }

    @AiService
    private interface PlanService {
        @Operation(prompt = "Answer", model = "test-model")
        fun answer(input: String): String
    }
}
