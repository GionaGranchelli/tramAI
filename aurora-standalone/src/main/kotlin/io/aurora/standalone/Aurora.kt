package io.aurora.standalone

import io.aurora.core.observation.NoOpOperationObserver
import io.aurora.core.observation.OperationObserver
import io.aurora.core.provider.ModelProvider
import io.aurora.core.provider.ProviderRegistry
import io.aurora.engine.AuroraEngine
import io.aurora.engine.ToolRegistry
import io.aurora.structured.JacksonStructuredOutputHandler
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

/**
 * Minimal composition module that wires core, engine, and structured output support.
 */
class Aurora private constructor(
    private val providerRegistry: ProviderRegistry,
    private val toolRegistry: ToolRegistry,
    private val operationObserver: OperationObserver,
) {
    /**
     * Creates a service proxy using the built-in Jackson structured output handler.
     */
    fun <T : Any> create(serviceType: KClass<T>): T = AuroraEngine(
        providerRegistry = providerRegistry,
        structuredOutputHandler = JacksonStructuredOutputHandler(),
        toolRegistry = toolRegistry,
        operationObserver = operationObserver,
    ).create(serviceType)

    companion object {
        @JvmStatic
        /**
         * Creates a standalone Aurora builder.
         */
        fun builder(): Builder = Builder()
    }

    /**
     * Builder for the standalone Aurora composition module.
     */
    class Builder {
        private val registryBuilder = ProviderRegistry.builder()
        private val tools = mutableMapOf<String, io.aurora.core.model.ResolvedTool>()
        private var operationObserver: OperationObserver = NoOpOperationObserver
        private val handler = JacksonStructuredOutputHandler()

        /**
         * Registers a provider with an optional explicit [name].
         */
        fun provider(
            provider: ModelProvider,
            name: String = provider.providerId(),
            default: Boolean = false,
        ): Builder = apply {
            registryBuilder.provider(name, provider, default)
        }

        /**
         * Registers one or more tools with the engine.
         */
        fun tools(vararg tools: io.aurora.core.model.AuroraTool<*, *>): Builder = apply {
            tools.forEach { tool ->
                if (this.tools.containsKey(tool.name)) {
                    throw io.aurora.core.exception.ConfigurationException("Duplicate tool name registered: ${tool.name}")
                }
                this.tools[tool.name] = object : io.aurora.core.model.ResolvedTool {
                    override val name: String = tool.name
                    override val description: String = tool.description
                    override val inputSchemaJson: String = handler.generateSchema(tool.inputType.createType())
                    override val idempotent: Boolean = tool.idempotent
                    override val sideEffectLevel: io.aurora.core.model.SideEffectLevel = tool.sideEffectLevel

                    override suspend fun execute(
                        input: Any,
                        context: io.aurora.core.model.ToolExecutionContext
                    ): io.aurora.core.model.ToolResult {
                        @Suppress("UNCHECKED_CAST")
                        val typedTool = tool as io.aurora.core.model.AuroraTool<Any, Any>
                        val typedInput = handler.deserialize(input, tool.inputType.createType())
                        
                        return try {
                            val result = typedTool.execute(typedInput, context)
                            io.aurora.core.model.ToolResult.Success(handler.serialize(result))
                        } catch (e: io.aurora.core.exception.ToolInvalidInputException) {
                            io.aurora.core.model.ToolResult.InvalidInput(e.message ?: "Invalid tool input")
                        } catch (e: Exception) {
                            if (tool.idempotent) {
                                io.aurora.core.model.ToolResult.TransientFailure(e)
                            } else {
                                io.aurora.core.model.ToolResult.PermanentFailure(e.message ?: "Tool execution failed")
                            }
                        }
                    }
                }
            }
        }
        
        fun tools(tools: Iterable<io.aurora.core.model.AuroraTool<*, *>>): Builder = apply {
            tools.forEach { tools(it) }
        }

        /**
         * Maps a logical model name to a registered provider.
         */
        fun model(
            modelName: String,
            providerName: String,
        ): Builder = apply {
            registryBuilder.model(modelName, providerName)
        }

        /**
         * Selects the default provider used when no explicit mapping applies.
         */
        fun defaultProvider(providerName: String): Builder = apply {
            registryBuilder.defaultProvider(providerName)
        }

        /**
         * Configures the observer used for engine attempts.
         */
        fun observer(observer: OperationObserver): Builder = apply {
            this.operationObserver = observer
        }

        /**
         * Builds an immutable standalone Aurora instance.
         */
        fun build(): Aurora = Aurora(
            providerRegistry = registryBuilder.build(),
            toolRegistry = io.aurora.engine.ToolRegistry(tools.toMap()),
            operationObserver = operationObserver,
        )
    }
}

/**
 * Reified convenience overload for [Aurora.create].
 */
inline fun <reified T : Any> Aurora.create(): T = create(T::class)

/**
 * Kotlin DSL entry point for constructing a standalone Aurora instance.
 */
fun Aurora(configure: Aurora.Builder.() -> Unit): Aurora = Aurora.builder()
    .apply(configure)
    .build()
