package dev.tramai.testing

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic in-memory provider for tests.
 */
class MockAiProvider private constructor(
    private val responsesByMethod: Map<String, List<String>>,
) : ModelProvider, RecordedRequestProvider {
    /** Requests captured in invocation order. */
    override val requests: MutableList<ModelRequest> = CopyOnWriteArrayList()
    private val responseIndexByMethod = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        val method = request.operationMethod
            ?: throw ProviderException("MockAiProvider requires request.operationMethod to be present")
        val responses = responsesByMethod[method]
            ?: throw ProviderException("No mock response configured for method '$method'")
        val index = responseIndexByMethod.computeIfAbsent(method) { AtomicInteger(0) }.getAndIncrement()
        val response = responses.getOrNull(index)
            ?: responses.lastOrNull()
            ?: throw ProviderException("No mock responses configured for method '$method'")
        return ModelResponse(content = response)
    }

    override fun providerId(): String = "mock"

    companion object {
        /**
         * DSL entry point for configuring the mock provider.
         */
        operator fun invoke(configure: Builder.() -> Unit): MockAiProvider = Builder()
            .apply(configure)
            .build()
    }

    /**
     * Builder for [MockAiProvider].
     */
    class Builder {
        private val responsesByMethod = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

        /**
         * Starts configuring responses for a service method name.
         */
        fun onMethod(methodName: String): MethodResponseBuilder = MethodResponseBuilder(methodName)

        /**
         * Builds an immutable mock provider snapshot.
         */
        fun build(): MockAiProvider = MockAiProvider(
            responsesByMethod = responsesByMethod.mapValues { (_, responses) -> responses.toList() },
        )

        /**
         * Fluent response builder scoped to a single method.
         */
        inner class MethodResponseBuilder(
            private val methodName: String,
        ) {
            /**
             * Appends a response returned on the next invocation of the configured method.
             */
            infix fun respondWith(response: String) {
                responsesByMethod.computeIfAbsent(methodName) { CopyOnWriteArrayList() } += response
            }
        }
    }
}
