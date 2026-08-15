package dev.tramai.engine.planning

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.System
import dev.tramai.core.annotations.User
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.engine.ReturnKind
import dev.tramai.engine.ToolRegistry
import kotlinx.coroutines.flow.Flow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OperationDefinitionCompilerTest {

    @Test
    fun `compiles suspend and blocking methods with declaration parameter names`() {
        val compiler = compiler()
        val suspendPlan = compiler.compile(SuspendAndBlockingService::class.java, method(SuspendAndBlockingService::class, "suspendEcho"), null)
        val blockingPlan = compiler.compile(SuspendAndBlockingService::class.java, method(SuspendAndBlockingService::class, "blockingCount"), null)

        assertThat(suspendPlan.definition.isSuspend).isTrue()
        assertThat(blockingPlan.definition.isSuspend).isFalse()
        assertThat(blockingPlan.definition.parameterNames).containsExactly("first", "second")
    }

    @Test
    fun `captures method message annotations`() {
        val plan = compiler().compile(AnnotatedMessagesService::class.java, method(AnnotatedMessagesService::class, "answer"), null)

        assertThat(plan.definition.systemAnnotations).containsExactly("System {input}")
        assertThat(plan.definition.userAnnotations).containsExactly("First {input}")
    }

    @Test
    fun `uses Java reflection fallback metadata deterministically`() {
        val method = JavaPlanningService::class.java.methods.single { it.name == "javaEcho" }
        val first = compiler().compile(JavaPlanningService::class.java, method, null)
        val second = compiler().compile(JavaPlanningService::class.java, method, null)

        assertThat(first.definition.parameterNames).isNotEmpty()
        assertThat(first.definition.parameterNames).isEqualTo(second.definition.parameterNames)
    }

    @Test
    fun `resolves all supported return kinds`() {
        val compiler = compiler()

        val structured = compiler.compile(ReturnKindsService::class.java, method(ReturnKindsService::class, "structured"), null).definition
        assertThat(structured.returnKind).isEqualTo(ReturnKind.STRUCTURED)
        assertThat(structured.returnType?.classifier).isEqualTo(Result::class)
        assertThat(compiler.compile(ReturnKindsService::class.java, method(ReturnKindsService::class, "string"), null).definition.returnKind)
            .isEqualTo(ReturnKind.STRING)
        assertThat(compiler.compile(ReturnKindsService::class.java, method(ReturnKindsService::class, "unit"), null).definition.returnKind)
            .isEqualTo(ReturnKind.UNIT)
        assertThat(compiler.compile(ReturnKindsService::class.java, method(ReturnKindsService::class, "stream"), null).definition.returnKind)
            .isEqualTo(ReturnKind.STREAMING)
    }

    @Test
    fun `resolves tool definitions from the registry`() {
        val plan = compiler().compile(ToolService::class.java, method(ToolService::class, "pay"), null)

        assertThat(plan.definition.toolDefinitions)
            .containsExactly(dev.tramai.core.model.ToolDefinition("payment", "Process a payment", "{\"type\":\"object\"}"))
    }

    @Test
    fun `rejects tools absent from the registry`() {
        assertThatThrownBy {
            emptyCompiler().compile(ToolService::class.java, method(ToolService::class, "pay"), null)
        }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("is not registered in the engine")
    }

    @Test
    fun `propagates operation annotation validation`() {
        assertThatThrownBy {
            compiler().compile(InvalidRetriesService::class.java, method(InvalidRetriesService::class, "answer"), null)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("maxRetries")
        assertThatThrownBy {
            compiler().compile(InvalidCacheService::class.java, method(InvalidCacheService::class, "answer"), null)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("cacheTtlMillis")
    }

    @Test
    fun `plan carries method identity and fingerprint`() {
        val plan = compiler().compile(ToolService::class.java, method(ToolService::class, "pay"), null)

        assertThat(plan.fingerprint).matches("[a-f0-9]{64}")
        assertThat(plan.serviceInterface).isEqualTo(ToolService::class.java.name)
        assertThat(plan.methodName).isEqualTo("pay")
    }

    private fun compiler() = OperationDefinitionCompiler(ToolRegistry(mapOf("payment" to FakeTool())), null, OperationFingerprintFactory())
    private fun emptyCompiler() = OperationDefinitionCompiler(ToolRegistry(), null, OperationFingerprintFactory())
    private fun method(serviceType: kotlin.reflect.KClass<*>, name: String) = serviceType.java.methods.single { it.name == name }

    private class FakeTool : ResolvedTool {
        override val name = "payment"
        override val description = "Process a payment"
        override val inputSchemaJson = "{\"type\":\"object\"}"
        override val idempotent = true
        override val sideEffectLevel = SideEffectLevel.READ_ONLY
        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult = ToolResult.Success("{}")
    }

    internal data class Result(val value: String)

    @AiService
    private interface SuspendAndBlockingService {
        @Operation(prompt = "Echo", model = "test-model")
        suspend fun suspendEcho(input: String): String

        @Operation(prompt = "Count", model = "test-model")
        fun blockingCount(first: Int, second: Int): Int
    }

    @AiService
    private interface AnnotatedMessagesService {
        @System("System {input}")
        @User("First {input}")
        @Operation(prompt = "Ignored", model = "test-model")
        fun answer(input: String): String
    }

    @AiService
    private interface ReturnKindsService {
        @Operation(prompt = "Structured", model = "test-model") fun structured(): Result
        @Operation(prompt = "String", model = "test-model") fun string(): String
        @Operation(prompt = "Unit", model = "test-model") fun unit()
        @Operation(prompt = "Stream", model = "test-model") fun stream(): Flow<String>
    }

    @AiService
    private interface ToolService {
        @Operation(prompt = "Pay", model = "test-model", tools = ["payment"])
        fun pay(amount: Double): String
    }

    @AiService
    private interface InvalidRetriesService {
        @Operation(prompt = "Invalid", model = "test-model", maxRetries = -1)
        fun answer(): String
    }

    @AiService
    private interface InvalidCacheService {
        @Operation(prompt = "Invalid", model = "test-model", cacheable = true, cacheTtlMillis = 0)
        fun answer(): String
    }
}
