package dev.tramai.engine

import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification

/**
 * Engine-owned cache for successful non-streaming operation results.
 */
interface OperationResponseCache {
    fun get(key: OperationCacheKey): CachedOperationResult?

    fun put(
        key: OperationCacheKey,
        value: CachedOperationResult,
        ttlMillis: Long,
    )
}

/**
 * Stable cache key derived from logical operation identity and rendered request content.
 *
 * `requestDigest` is a SHA-256 digest of canonically rendered messages. It is
 * not encryption. The digest avoids retaining raw prompt content directly in
 * the key; a configurable HMAC strategy is intentionally deferred.
 */
data class OperationCacheKey(
    val schemaVersion: Int = 1,
    val serviceInterface: String,
    val methodName: String,
    val requestedModel: String,
    val explicitProvider: String?,
    val requestDigest: String,
    val operationFingerprint: String,
    val securityPartition: CacheSecurityPartition,
)

data class CachedResponseProvenance(
    val providerId: String,
    val modelName: String,
    val dataClassification: DataClassification?,
    val classificationSource: ClassificationSource?,
    val modelRegistryEntryId: String? = null,
    val modelRevision: String? = null,
    val modelArtifactDigest: ModelArtifactDigest? = null,
)

data class CachedOperationResult(
    val value: Any,
    val provenance: CachedResponseProvenance,
)

data class CacheSecurityPartition(
    val dataClassification: DataClassification?,
    val classificationSource: ClassificationSource?,
)

object NoOpOperationResponseCache : OperationResponseCache {
    override fun get(key: OperationCacheKey): CachedOperationResult? = null

    override fun put(
        key: OperationCacheKey,
        value: CachedOperationResult,
        ttlMillis: Long,
    ) = Unit
}
