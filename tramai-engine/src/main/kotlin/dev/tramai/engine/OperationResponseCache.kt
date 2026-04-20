package dev.tramai.engine

/**
 * Engine-owned cache for successful non-streaming operation results.
 */
interface OperationResponseCache {
    fun get(key: OperationCacheKey): Any?

    fun put(
        key: OperationCacheKey,
        value: Any,
        ttlMillis: Long,
    )
}

/**
 * Stable cache key derived from logical operation identity and rendered request content.
 */
data class OperationCacheKey(
    val serviceInterface: String,
    val methodName: String,
    val requestedModel: String,
    val explicitProvider: String?,
    val messages: List<CachedMessage>,
)

data class CachedMessage(
    val role: String,
    val content: String,
)

object NoOpOperationResponseCache : OperationResponseCache {
    override fun get(key: OperationCacheKey): Any? = null

    override fun put(
        key: OperationCacheKey,
        value: Any,
        ttlMillis: Long,
    ) = Unit
}
