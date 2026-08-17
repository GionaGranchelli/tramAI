package dev.tramai.engine.cache

import dev.tramai.core.exception.CachedModelProvenanceMismatchException
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.engine.CachedOperationResult
import dev.tramai.engine.CachedResponseProvenance
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.OperationCacheKey
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.PolicyContextBuilder
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ReturnKind
import dev.tramai.engine.buildRequestDigest
import dev.tramai.engine.planning.OperationFingerprintFactory

internal class OperationCacheCoordinator(
    private val responseCache: OperationResponseCache,
    private val operationInterceptor: OperationInterceptor,
    private val dlpInterceptor: DlpInterceptor,
    private val modelRegistrySettings: ModelRegistrySettings,
    private val modelRegistryEnforcer: ModelRegistryEnforcer,
    private val policyHelper: PolicyEnforcementHelper,
) {
    fun createKey(request: OperationCacheKeyRequest): OperationCacheKey? {
        if (!request.operation.cacheable ||
            request.returnKind == ReturnKind.STREAMING ||
            request.toolDefinitions.isNotEmpty() ||
            operationInterceptor !== NoOpOperationInterceptor ||
            dlpInterceptor !== NoOpDlpInterceptor
        ) {
            return null
        }
        return OperationCacheKey(
            serviceInterface = request.serviceInterface,
            methodName = request.methodName,
            requestedModel = request.requestedModel,
            explicitProvider = request.explicitProvider,
            requestDigest = buildRequestDigest(request.digestSource),
            operationFingerprint = request.operationFingerprint
                ?: OperationFingerprintFactory().create(request.toolDefinitions, request.operation),
            securityPartition = request.securityPartition,
        )
    }

    suspend fun lookup(request: OperationCacheLookupRequest): OperationCacheLookupResult {
        if (request.conversationId != null ||
            operationInterceptor !== NoOpOperationInterceptor ||
            dlpInterceptor !== NoOpDlpInterceptor
        ) {
            return OperationCacheLookupResult.Miss(null)
        }
        val cached = responseCache.get(request.key) ?: return OperationCacheLookupResult.Miss(request.key)
        try {
            validateCachedEntry(request.key, cached)
            authorizeCachedModelProvenance(cached.provenance)
            enforceCacheReusePolicies(request.key, cached, request.securityContext, request.correlationId)
            return OperationCacheLookupResult.Hit(cached.value)
        } catch (_: CachedModelProvenanceMismatchException) {
            responseCache.invalidate(request.key)
            return OperationCacheLookupResult.Miss(request.key)
        }
    }

    fun store(request: OperationCacheStoreRequest) {
        if (request.conversationId != null ||
            operationInterceptor !== NoOpOperationInterceptor ||
            dlpInterceptor !== NoOpDlpInterceptor
        ) {
            return
        }
        responseCache.put(
            key = request.key,
            value = CachedOperationResult(
                value = request.value,
                provenance = CachedResponseProvenance(
                    providerId = request.providerId,
                    modelName = request.modelName,
                    dataClassification = request.securityContext.dataClassification,
                    classificationSource = request.securityContext.classificationSource,
                    modelRegistryEntryId = request.approvedModel?.registryEntryId,
                    modelRevision = request.approvedModel?.revision,
                    modelArtifactDigest = request.approvedModel?.artifactDigest,
                ),
            ),
            ttlMillis = request.ttlMillis,
        )
    }

    private suspend fun authorizeCachedModelProvenance(provenance: CachedResponseProvenance) {
        if (!modelRegistrySettings.enabled) return
        val current = modelRegistryEnforcer.authorize(provenance.providerId, provenance.modelName)
            ?: error("ModelRegistryEnforcer.authorize returned null when registry is enabled")
        if (current.registryEntryId != provenance.modelRegistryEntryId || current.revision != provenance.modelRevision || current.artifactDigest != provenance.modelArtifactDigest) throw CachedModelProvenanceMismatchException()
    }

    private suspend fun enforceCacheReusePolicies(cacheKey: OperationCacheKey, cached: CachedOperationResult, securityContext: ExecutionSecurityContext, correlationId: String) {
        policyHelper.enforce(policyHelper.buildContext(EnforcementPoint.BEFORE_PROVIDER_RESOLUTION, correlationId).modelName(cacheKey.requestedModel).applySecurityContext(securityContext).attribute("cacheReuse", "true").build())
        policyHelper.enforce(policyHelper.buildContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, correlationId).providerId(cached.provenance.providerId).modelName(cached.provenance.modelName).applySecurityContext(securityContext).attribute("cacheReuse", "true").build())
        policyHelper.enforce(policyHelper.buildContext(EnforcementPoint.BEFORE_RESPONSE_RETURN, correlationId).providerId(cached.provenance.providerId).modelName(cached.provenance.modelName).applySecurityContext(securityContext).attribute("cacheReuse", "true").build())
    }

    private fun validateCachedEntry(key: OperationCacheKey, cached: CachedOperationResult) {
        val provenance = cached.provenance
        check(provenance.providerId.isNotBlank() && provenance.modelName.isNotBlank()) { "Cached entry envelope has blank provider provenance" }
        check(provenance.dataClassification == key.securityPartition.dataClassification && provenance.classificationSource == key.securityPartition.classificationSource) {
            "Cached entry envelope mismatch: key partition ${key.securityPartition} != cached provenance partition (${provenance.dataClassification}, ${provenance.classificationSource})"
        }
    }
}

private fun PolicyContextBuilder.applySecurityContext(securityContext: ExecutionSecurityContext): PolicyContextBuilder =
    dataClassification(securityContext.dataClassification).classificationSource(securityContext.classificationSource)
