package dev.tramai.core.provider

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse

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
