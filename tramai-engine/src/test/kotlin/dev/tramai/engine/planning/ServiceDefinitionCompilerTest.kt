package dev.tramai.engine.planning

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.engine.ReturnKind
import dev.tramai.engine.ToolRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ServiceDefinitionCompilerTest {

    @Test
    fun `compiles an annotated Kotlin service into operation plans`() {
        val definition = compiler().compile(TestPlanningService::class)

        assertThat(definition.serviceType).isEqualTo(TestPlanningService::class)
        assertThat(definition.systemPrompt).isNull()
        assertThat(definition.operations.keys.map { it.name }).containsExactlyInAnyOrder("echo", "count", "pay")
        assertThat(definition.operations[method(TestPlanningService::class, "pay")]?.definition?.toolDefinitions)
            .extracting("name")
            .containsExactly("payment")
    }

    @Test
    fun `extracts class system prompt and treats a blank prompt as absent`() {
        assertThat(compiler().compile(PromptedPlanningService::class).systemPrompt).isEqualTo("Be precise")
        assertThat(compiler().compile(BlankPromptPlanningService::class).systemPrompt).isNull()
    }

    @Test
    fun `rejects a service class`() {
        assertThatThrownBy { compiler().compile(NotAnInterface::class) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("must be an interface")
    }

    @Test
    fun `rejects an interface without AiService`() {
        assertThatThrownBy { compiler().compile(MissingAiService::class) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("must be annotated with @AiService")
    }

    @Test
    fun `rejects methods without Operation`() {
        assertThatThrownBy { compiler().compile(ServiceWithUnannotatedMethod::class) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("must be annotated with @Operation")
    }

    @Test
    fun `does not compile Object methods as operations`() {
        val operations = compiler().compile(TestPlanningService::class).operations

        assertThat(operations.keys.map { it.name }).doesNotContain("toString", "equals", "hashCode")
    }

    @Test
    fun `compilation has deterministic operation metadata`() {
        val first = compiler().compile(TestPlanningService::class)
        val second = compiler().compile(TestPlanningService::class)

        assertThat(first.operations).isEqualTo(second.operations)
    }

    @Test
    fun `compiles an actual Java service interface`() {
        val plan = compiler().compile(JavaPlanningService::class).operations.values.single()

        assertThat(plan.methodName).isEqualTo("javaEcho")
        assertThat(plan.definition.returnKind).isEqualTo(ReturnKind.STRING)
        assertThat(plan.definition.isSuspend).isFalse()
        assertThat(plan.definition.parameterNames).isNotEmpty()
    }

    private fun compiler() = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(
            ToolRegistry(mapOf("payment" to FakeTool())),
            null,
            OperationFingerprintFactory(),
        ),
    )

    private fun method(serviceType: kotlin.reflect.KClass<*>, name: String) =
        serviceType.java.methods.single { it.name == name }

    private class FakeTool : ResolvedTool {
        override val name = "payment"
        override val description = "Process a payment"
        override val inputSchemaJson = "{\"type\":\"object\"}"
        override val idempotent = true
        override val sideEffectLevel = SideEffectLevel.READ_ONLY

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult = ToolResult.Success("{}")
    }

    @AiService
    internal interface TestPlanningService {
        @Operation(prompt = "Echo", model = "test-model")
        suspend fun echo(input: String): String

        @Operation(prompt = "Count", model = "test-model")
        fun count(a: Int, b: Int): Int

        @Operation(prompt = "Parse", model = "test-model", tools = ["payment"])
        fun pay(amount: Double): String
    }

    @AiService
    @SystemPrompt("Be precise")
    private interface PromptedPlanningService {
        @Operation(prompt = "Prompt", model = "test-model")
        fun answer(input: String): String
    }

    @AiService
    @SystemPrompt("   ")
    private interface BlankPromptPlanningService {
        @Operation(prompt = "Prompt", model = "test-model")
        fun answer(input: String): String
    }

    private class NotAnInterface

    private interface MissingAiService {
        @Operation(prompt = "Prompt", model = "test-model")
        fun answer(input: String): String
    }

    @AiService
    private interface ServiceWithUnannotatedMethod {
        fun answer(input: String): String
    }
}
