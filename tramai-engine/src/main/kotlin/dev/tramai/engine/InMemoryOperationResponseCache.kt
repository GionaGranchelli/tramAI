package dev.tramai.engine

/**
 * Simple in-memory cache intended for local and lightweight production use.
 */
class InMemoryOperationResponseCache(
    private val maxEntries: Int = 1_000,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : OperationResponseCache {
    private val entries = object : LinkedHashMap<OperationCacheKey, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<OperationCacheKey, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    init {
        require(maxEntries > 0) { "InMemoryOperationResponseCache.maxEntries must be greater than zero" }
    }

    @Synchronized
    override fun get(key: OperationCacheKey): CachedOperationResult? {
        val entry = entries[key] ?: return null
        if (clockMillis() >= entry.expiresAtMillis) {
            entries.remove(key)
            return null
        }
        return CachedOperationResult(
            value = entry.value,
            provenance = entry.provenance,
        )
    }

    @Synchronized
    override fun put(
        key: OperationCacheKey,
        value: CachedOperationResult,
        ttlMillis: Long,
    ) {
        require(ttlMillis > 0) { "Cache TTL must be greater than zero" }
        entries[key] = CacheEntry(
            value = value.value,
            expiresAtMillis = clockMillis() + ttlMillis,
            provenance = value.provenance,
        )
    }

    @Synchronized
    internal fun snapshotKeys(): Set<OperationCacheKey> = entries.keys.toSet()

    @Synchronized
    internal fun peek(key: OperationCacheKey): CachedOperationResult? = get(key)
}

private data class CacheEntry(
    val value: Any,
    val expiresAtMillis: Long,
    val provenance: CachedResponseProvenance,
)
