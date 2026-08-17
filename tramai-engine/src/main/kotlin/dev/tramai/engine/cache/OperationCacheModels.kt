package dev.tramai.engine.cache

import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.model.ToolDefinition
import dev.tramai.engine.CacheSecurityPartition
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationCacheKey
import dev.tramai.engine.ReturnKind

internal data class OperationCacheKeyRequest(
    val digestSource: List<dev.tramai.core.model.Message>,
    val securityPartition: CacheSecurityPartition,
    val operationFingerprint: String?,
    val requestedModel: String,
    val explicitProvider: String?,
    val serviceInterface: String,
    val methodName: String,
    val toolDefinitions: List<ToolDefinition>,
    val operation: Operation,
    val returnKind: ReturnKind,
)

internal data class OperationCacheLookupRequest(
    val key: OperationCacheKey,
    val securityContext: ExecutionSecurityContext,
    val correlationId: String,
    val conversationId: String?,
)

internal sealed interface OperationCacheLookupResult {
    data class Hit(val value: Any) : OperationCacheLookupResult
    data class Miss(val key: OperationCacheKey?) : OperationCacheLookupResult
}

internal data class OperationCacheStoreRequest(
    val key: OperationCacheKey,
    val value: Any,
    val providerId: String,
    val modelName: String,
    val securityContext: ExecutionSecurityContext,
    val conversationId: String?,
    val approvedModel: RegisteredModel?,
    val ttlMillis: Long,
)
