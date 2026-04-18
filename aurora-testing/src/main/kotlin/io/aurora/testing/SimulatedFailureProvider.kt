package io.aurora.testing

import io.aurora.core.exception.ProviderException
import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse
import io.aurora.core.provider.ModelProvider

/**
 * Deterministic provider that can return responses or throw configured failures in sequence.
 */
class SimulatedFailureProvider private constructor(
    private val outcomesByMethod: Map<String, List<Outcome>>,
) : ModelProvider, RecordedRequestProvider {
    override val requests: MutableList<ModelRequest> = mutableListOf()
    private val outcomeIndexByMethod = mutableMapOf<String, Int>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        val method = request.operationMethod
            ?: throw ProviderException("SimulatedFailureProvider requires request.operationMethod to be present")
        val outcomes = outcomesByMethod[method]
            ?: throw ProviderException("No simulated outcome configured for method '$method'")
        val index = outcomeIndexByMethod.getOrDefault(method, 0)
        val outcome = outcomes.getOrNull(index)
            ?: outcomes.lastOrNull()
            ?: throw ProviderException("No simulated outcomes configured for method '$method'")
        outcomeIndexByMethod[method] = index + 1

        return when (outcome) {
            is Outcome.Response -> outcome.response
            is Outcome.Failure -> throw outcome.error
        }
    }

    override fun providerId(): String = "simulated-failure"

    companion object {
        /**
         * DSL entry point for configuring the simulated provider.
         */
        operator fun invoke(configure: Builder.() -> Unit): SimulatedFailureProvider = Builder()
            .apply(configure)
            .build()
    }

    /**
     * Builder for [SimulatedFailureProvider].
     */
    class Builder {
        private val outcomesByMethod = linkedMapOf<String, MutableList<Outcome>>()

        /**
         * Starts configuring outcomes for a service method name.
         */
        fun onMethod(methodName: String): MethodOutcomeBuilder = MethodOutcomeBuilder(methodName)

        /**
         * Builds an immutable provider snapshot.
         */
        fun build(): SimulatedFailureProvider = SimulatedFailureProvider(
            outcomesByMethod = outcomesByMethod.mapValues { (_, outcomes) -> outcomes.toList() },
        )

        /**
         * Fluent outcome builder scoped to a single method.
         */
        inner class MethodOutcomeBuilder(
            private val methodName: String,
        ) {
            /**
             * Appends a successful text response returned on the next invocation.
             */
            infix fun respondWith(response: String) {
                respondWith(ModelResponse(content = response))
            }

            /**
             * Appends a successful full provider response returned on the next invocation.
             */
            infix fun respondWith(response: ModelResponse) {
                append(Outcome.Response(response))
            }

            /**
             * Appends a retryable provider failure.
             */
            fun retryableFailure(
                message: String,
                statusCode: Int? = null,
            ) {
                append(
                    Outcome.Failure(
                        ProviderException(
                            message = message,
                            statusCode = statusCode,
                            retryable = true,
                        ),
                    ),
                )
            }

            /**
             * Appends a non-retryable provider failure.
             */
            fun nonRetryableFailure(
                message: String,
                statusCode: Int? = null,
            ) {
                append(
                    Outcome.Failure(
                        ProviderException(
                            message = message,
                            statusCode = statusCode,
                            retryable = false,
                        ),
                    ),
                )
            }

            /**
             * Appends an arbitrary failure thrown on the next invocation.
             */
            fun failWith(error: Throwable) {
                append(Outcome.Failure(error))
            }

            private fun append(outcome: Outcome) {
                outcomesByMethod.getOrPut(methodName) { mutableListOf() } += outcome
            }
        }
    }

    private sealed interface Outcome {
        data class Response(val response: ModelResponse) : Outcome

        data class Failure(val error: Throwable) : Outcome
    }
}
