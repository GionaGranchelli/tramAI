package io.aurora.core.provider

import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse

interface ModelProvider {
    suspend fun complete(request: ModelRequest): ModelResponse

    fun providerId(): String = this::class.simpleName ?: "unknown"
}
