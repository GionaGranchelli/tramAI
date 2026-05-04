package dev.tramai.platform

import dev.tramai.orchestration.ExternalStepExecutor
import dev.tramai.orchestration.ExternalStepExecutorFactory
import dev.tramai.orchestration.ExternalStepExecutorRegistry
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
import dev.tramai.server.WorkflowRegistry
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PluginWorkflowStartupValidatorTest {
    @Test
    fun `validator fails when a registered workflow requires a missing plugin executor`() {
        val workflowRegistry = WorkflowRegistry()
        workflowRegistry.register(
            workflow = workflow<Map<String, Any?>>("plugin-echo") {
                pluginStep(name = "echo", type = "demo.echo")
            }.build { it },
            stateCodec = MapStateCodec,
            defaultPersistence = { null },
        )

        assertThatThrownBy {
            PluginWorkflowStartupValidator(
                workflowRegistry = workflowRegistry,
                executorResolver = ExternalStepExecutorRegistry(),
            ).validate()
        }
            .isInstanceOf(PluginWorkflowStartupValidationException::class.java)
            .hasMessageContaining("plugin-echo")
            .hasMessageContaining("demo.echo")
    }

    @Test
    fun `validator passes when every required plugin executor is registered`() {
        val workflowRegistry = WorkflowRegistry()
        workflowRegistry.register(
            workflow = workflow<Map<String, Any?>>("plugin-echo") {
                pluginStep(name = "echo", type = "demo.echo")
            }.build { it },
            stateCodec = MapStateCodec,
            defaultPersistence = { null },
        )
        val executorRegistry = ExternalStepExecutorRegistry().apply {
            register(TestPluginExecutorFactory("demo.echo"))
        }

        assertThatCode {
            PluginWorkflowStartupValidator(
                workflowRegistry = workflowRegistry,
                executorResolver = executorRegistry,
            ).validate()
        }.doesNotThrowAnyException()
    }
}

private object MapStateCodec : WorkflowStateCodec<Map<String, Any?>> {
    override fun encode(state: Map<String, Any?>): String = state.toString()

    override fun decode(payload: String): Map<String, Any?> = emptyMap()
}

private class TestPluginExecutorFactory(
    override val typeId: String,
) : ExternalStepExecutorFactory {
    override fun create(): ExternalStepExecutor = ExternalStepExecutor { emptyMap() }
}
