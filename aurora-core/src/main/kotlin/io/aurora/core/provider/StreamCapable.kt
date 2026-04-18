package io.aurora.core.provider

import io.aurora.core.model.ModelRequest
import io.aurora.core.model.StreamChunk
import kotlinx.coroutines.flow.Flow

/**
 * Capability interface for providers that support incremental streaming.
 */
interface StreamCapable {
    /**
     * Executes a completion request and returns an incremental flow of chunks.
     *
     * Cancellations in the consuming context should propagate to the underlying
     * provider transport to stop work.
     */
    suspend fun stream(request: ModelRequest): Flow<StreamChunk>
}
