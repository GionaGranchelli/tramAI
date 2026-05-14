package dev.tramai.core.provider

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse

/**
 * Capabilities that a [ModelProvider] may support.
 */
enum class ProviderCapability {
    VISION,
    TOOL_CALLING,
    STRUCTURED_OUTPUT,
    STREAMING,
}

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

    /**
     * Returns true if this provider supports the given [capability].
     * Default implementation returns false for backward compatibility.
     */
    fun supportsCapability(capability: ProviderCapability): Boolean = false
}
