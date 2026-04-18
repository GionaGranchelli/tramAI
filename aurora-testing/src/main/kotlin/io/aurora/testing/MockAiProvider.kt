package io.aurora.testing

import io.aurora.core.exception.ProviderException
import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse
import io.aurora.core.provider.ModelProvider

/**
 * Deterministic in-memory provider for tests.
 */
class MockAiProvider private constructor(
    private val responsesByMethod: Map<String, List<String>>,
) : ModelProvider {
    /** Requests captured in invocation order. */
    val requests: MutableList<ModelRequest> = mutableListOf()
    private val responseIndexByMethod = mutableMapOf<String, Int>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        val method = request.operationMethod
            ?: throw ProviderException("MockAiProvider requires request.operationMethod to be present")
        val responses = responsesByMethod[method]
            ?: throw ProviderException("No mock response configured for method '$method'")
        val index = responseIndexByMethod.getOrDefault(method, 0)
        val response = responses.getOrNull(index)
            ?: responses.lastOrNull()
            ?: throw ProviderException("No mock responses configured for method '$method'")
        responseIndexByMethod[method] = index + 1
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
        private val responsesByMethod = linkedMapOf<String, MutableList<String>>()

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
                responsesByMethod.getOrPut(methodName) { mutableListOf() } += response
            }
        }
    }
}
