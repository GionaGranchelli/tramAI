package io.aurora.core.provider

import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse

/**
 * Provider abstraction implemented by transport modules.
 */
interface ModelProvider {
    /**
     * Executes a completion request against the backing provider.
     */
    suspend fun complete(request: ModelRequest): ModelResponse

    /**
     * Stable identifier used by the registry and observation layer.
     */
    fun providerId(): String = this::class.simpleName ?: "unknown"
}
