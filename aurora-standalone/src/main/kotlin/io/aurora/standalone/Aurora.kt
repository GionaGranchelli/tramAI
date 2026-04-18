package io.aurora.standalone

import io.aurora.core.observation.NoOpOperationObserver
import io.aurora.core.observation.OperationObserver
import io.aurora.core.provider.ModelProvider
import io.aurora.core.provider.ProviderRegistry
import io.aurora.engine.AuroraEngine
import io.aurora.structured.JacksonStructuredOutputHandler
import kotlin.reflect.KClass

class Aurora private constructor(
    private val providerRegistry: ProviderRegistry,
    private val operationObserver: OperationObserver,
) {
    fun <T : Any> create(serviceType: KClass<T>): T = AuroraEngine(
        providerRegistry = providerRegistry,
        structuredOutputHandler = JacksonStructuredOutputHandler(),
        operationObserver = operationObserver,
    ).create(serviceType)

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder {
        private val registryBuilder = ProviderRegistry.builder()
        private var operationObserver: OperationObserver = NoOpOperationObserver

        fun provider(
            provider: ModelProvider,
            name: String = provider.providerId(),
            default: Boolean = false,
        ): Builder = apply {
            registryBuilder.provider(name, provider, default)
        }

        fun model(
            modelName: String,
            providerName: String,
        ): Builder = apply {
            registryBuilder.model(modelName, providerName)
        }

        fun defaultProvider(providerName: String): Builder = apply {
            registryBuilder.defaultProvider(providerName)
        }

        fun observer(observer: OperationObserver): Builder = apply {
            this.operationObserver = observer
        }

        fun build(): Aurora = Aurora(
            providerRegistry = registryBuilder.build(),
            operationObserver = operationObserver,
        )
    }
}

inline fun <reified T : Any> Aurora.create(): T = create(T::class)

fun Aurora(configure: Aurora.Builder.() -> Unit): Aurora = Aurora.builder()
    .apply(configure)
    .build()
