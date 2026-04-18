package io.aurora.testing

import io.aurora.core.exception.ProviderException
import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse
import io.aurora.core.provider.ModelProvider

class MockAiProvider private constructor(
    private val responsesByMethod: Map<String, List<String>>,
) : ModelProvider {
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
        operator fun invoke(configure: Builder.() -> Unit): MockAiProvider = Builder()
            .apply(configure)
            .build()
    }

    class Builder {
        private val responsesByMethod = linkedMapOf<String, MutableList<String>>()

        fun onMethod(methodName: String): MethodResponseBuilder = MethodResponseBuilder(methodName)

        fun build(): MockAiProvider = MockAiProvider(
            responsesByMethod = responsesByMethod.mapValues { (_, responses) -> responses.toList() },
        )

        inner class MethodResponseBuilder(
            private val methodName: String,
        ) {
            infix fun respondWith(response: String) {
                responsesByMethod.getOrPut(methodName) { mutableListOf() } += response
            }
        }
    }
}
