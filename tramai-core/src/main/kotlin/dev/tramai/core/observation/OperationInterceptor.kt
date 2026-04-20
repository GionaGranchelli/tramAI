package dev.tramai.core.observation

import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelResponse

/**
 * Interceptor that can inspect and modify request messages and provider responses.
 *
 * Useful for PII redaction, logging, and security auditing.
 */
interface OperationInterceptor {
    /**
     * Intercepts the request messages before they are sent to the provider.
     * Returns the list of messages to be sent (potentially modified).
     */
    fun interceptRequest(
        context: OperationCallContext,
        messages: List<Message>
    ): List<Message> = messages

    /**
     * Intercepts the provider response before it is processed by the engine or returned to the user.
     * Returns the response to be used (potentially modified).
     */
    fun interceptResponse(
        context: OperationCallContext,
        response: ModelResponse
    ): ModelResponse = response
}

/**
 * Composite interceptor that applies multiple interceptors in order.
 */
class CompositeOperationInterceptor(
    private val interceptors: List<OperationInterceptor>
) : OperationInterceptor {
    override fun interceptRequest(
        context: OperationCallContext,
        messages: List<Message>
    ): List<Message> = interceptors.fold(messages) { current, interceptor ->
        interceptor.interceptRequest(context, current)
    }

    override fun interceptResponse(
        context: OperationCallContext,
        response: ModelResponse
    ): ModelResponse = interceptors.fold(response) { current, interceptor ->
        interceptor.interceptResponse(context, current)
    }
}

object NoOpOperationInterceptor : OperationInterceptor
