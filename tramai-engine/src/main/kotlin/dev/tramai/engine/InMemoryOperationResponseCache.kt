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
    override fun get(key: OperationCacheKey): Any? {
        val entry = entries[key] ?: return null
        if (clockMillis() >= entry.expiresAtMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    override fun put(
        key: OperationCacheKey,
        value: Any,
        ttlMillis: Long,
    ) {
        require(ttlMillis > 0) { "Cache TTL must be greater than zero" }
        entries[key] = CacheEntry(
            value = value,
            expiresAtMillis = clockMillis() + ttlMillis,
        )
    }
}

private data class CacheEntry(
    val value: Any,
    val expiresAtMillis: Long,
)
