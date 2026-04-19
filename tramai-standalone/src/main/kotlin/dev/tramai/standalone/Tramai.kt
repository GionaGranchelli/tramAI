package dev.tramai.standalone

import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.ToolRegistry
import dev.tramai.structured.JacksonStructuredOutputHandler
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

/**
 * Minimal composition module that wires core, engine, and structured output support.
 */
class Tramai private constructor(
    private val providerRegistry: ProviderRegistry,
    private val toolRegistry: ToolRegistry,
    private val operationObserver: OperationObserver,
) {
    /**
     * Creates a service proxy using the built-in Jackson structured output handler.
     */
    fun <T : Any> create(serviceType: KClass<T>): T = TramaiEngine(
        providerRegistry = providerRegistry,
        structuredOutputHandler = JacksonStructuredOutputHandler(),
        toolRegistry = toolRegistry,
        operationObserver = operationObserver,
    ).create(serviceType)

    companion object {
        @JvmStatic
        /**
         * Creates a standalone Tramai builder.
         */
        fun builder(): Builder = Builder()
    }

    /**
     * Builder for the standalone Tramai composition module.
     */
    class Builder {
        private val registryBuilder = ProviderRegistry.builder()
        private val tools = mutableMapOf<String, dev.tramai.core.model.ResolvedTool>()
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
        fun tools(vararg tools: dev.tramai.core.model.TramaiTool<*, *>): Builder = apply {
            tools.forEach { tool ->
                if (this.tools.containsKey(tool.name)) {
                    throw dev.tramai.core.exception.ConfigurationException("Duplicate tool name registered: ${tool.name}")
                }
                this.tools[tool.name] = object : dev.tramai.core.model.ResolvedTool {
                    override val name: String = tool.name
                    override val description: String = tool.description
                    override val inputSchemaJson: String = handler.generateSchema(tool.inputType.createType())
                    override val idempotent: Boolean = tool.idempotent
                    override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel = tool.sideEffectLevel

                    override suspend fun execute(
                        input: Any,
                        context: dev.tramai.core.model.ToolExecutionContext
                    ): dev.tramai.core.model.ToolResult {
                        @Suppress("UNCHECKED_CAST")
                        val typedTool = tool as dev.tramai.core.model.TramaiTool<Any, Any>
                        val typedInput = handler.deserialize(input, tool.inputType.createType())
                        
                        return try {
                            val result = typedTool.execute(typedInput, context)
                            dev.tramai.core.model.ToolResult.Success(handler.serialize(result))
                        } catch (e: dev.tramai.core.exception.ToolInvalidInputException) {
                            dev.tramai.core.model.ToolResult.InvalidInput(e.message ?: "Invalid tool input")
                        } catch (e: Exception) {
                            if (tool.idempotent) {
                                dev.tramai.core.model.ToolResult.TransientFailure(e)
                            } else {
                                dev.tramai.core.model.ToolResult.PermanentFailure(e.message ?: "Tool execution failed")
                            }
                        }
                    }
                }
            }
        }
        
        fun tools(tools: Iterable<dev.tramai.core.model.TramaiTool<*, *>>): Builder = apply {
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
         * Builds an immutable standalone Tramai instance.
         */
        fun build(): Tramai = Tramai(
            providerRegistry = registryBuilder.build(),
            toolRegistry = dev.tramai.engine.ToolRegistry(tools.toMap()),
            operationObserver = operationObserver,
        )
    }
}

/**
 * Reified convenience overload for [Tramai.create].
 */
inline fun <reified T : Any> Tramai.create(): T = create(T::class)

/**
 * Kotlin DSL entry point for constructing a standalone Tramai instance.
 */
fun Tramai(configure: Tramai.Builder.() -> Unit): Tramai = Tramai.builder()
    .apply(configure)
    .build()
