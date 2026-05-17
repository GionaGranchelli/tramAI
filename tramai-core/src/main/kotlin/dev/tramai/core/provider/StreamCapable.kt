package dev.tramai.core.provider

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.StreamChunk
import kotlinx.coroutines.flow.Flow

/**
 * Capability interface for providers that support incremental streaming.
 */
fun interface StreamCapable {
    /**
     * Executes a completion request and returns an incremental flow of chunks.
     *
     * Cancellations in the consuming context should propagate to the underlying
     * provider transport to stop work.
     */
    fun stream(request: ModelRequest): Flow<StreamChunk>
}
