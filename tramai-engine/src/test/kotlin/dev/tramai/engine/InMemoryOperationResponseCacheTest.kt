package dev.tramai.engine

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InMemoryOperationResponseCacheTest {

    private val key = OperationCacheKey(
        serviceInterface = "test",
        methodName = "analyze",
        requestedModel = "model",
        explicitProvider = null,
        requestDigest = "digest",
        operationFingerprint = "fp",
        securityPartition = CacheSecurityPartition(
            dataClassification = null,
            classificationSource = null,
        ),
    )

    private val cachedResult = CachedOperationResult(
        value = "result",
        provenance = CachedResponseProvenance(
            providerId = "p",
            modelName = "m",
            dataClassification = null,
            classificationSource = null,
        ),
    )

    @Test
    fun `zero TTL is rejected`() {
        val cache = InMemoryOperationResponseCache()
        assertThatThrownBy {
            cache.put(key, cachedResult, ttlMillis = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("TTL")
    }

    @Test
    fun `negative TTL is rejected`() {
        val cache = InMemoryOperationResponseCache()
        assertThatThrownBy {
            cache.put(key, cachedResult, ttlMillis = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("TTL")
    }
}
