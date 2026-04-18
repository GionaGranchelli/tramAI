package io.aurora.standalone

import io.aurora.core.observation.NoOpOperationObserver
import io.aurora.core.observation.OperationObserver
import io.aurora.core.provider.ModelProvider
import io.aurora.core.provider.ProviderRegistry
import io.aurora.engine.AuroraEngine
import io.aurora.structured.JacksonStructuredOutputHandler
import kotlin.reflect.KClass

/**
 * Minimal composition module that wires core, engine, and structured output support.
 */
class Aurora private constructor(
    private val providerRegistry: ProviderRegistry,
    private val operationObserver: OperationObserver,
) {
    /**
     * Creates a service proxy using the built-in Jackson structured output handler.
     */
    fun <T : Any> create(serviceType: KClass<T>): T = AuroraEngine(
        providerRegistry = providerRegistry,
        structuredOutputHandler = JacksonStructuredOutputHandler(),
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
        private var operationObserver: OperationObserver = NoOpOperationObserver

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
