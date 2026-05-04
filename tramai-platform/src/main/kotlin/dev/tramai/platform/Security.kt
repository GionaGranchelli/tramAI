package dev.tramai.platform

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class CreateApiKeyRequest(
    val teamId: String,
    val projectId: String,
    val name: String,
    val scopes: Set<String>,
    val burstCapacity: Int = 10,
    val refillTokensPerSecond: Double = 1.0,
)

data class ApiKeyResponse(
    val id: String,
    val teamId: String,
    val projectId: String,
    val prefix: String,
    val name: String,
    val scopes: Set<String>,
    val burstCapacity: Int,
    val refillTokensPerSecond: Double,
    val createdAt: Instant,
    val revokedAt: Instant?,
    val lastUsedAt: Instant?,
    val key: String? = null,
)

class AuthenticationException(
    message: String,
) : RuntimeException(message)

class AuthorizationException(
    message: String,
) : RuntimeException(message)

data class RateLimitDecision(
    val allowed: Boolean,
    val limit: Int,
    val remaining: Int,
    val retryAfterSeconds: Long,
    val resetAtEpochSeconds: Long,
)

class RateLimitExceededException(
    val decision: RateLimitDecision,
) : RuntimeException("API key rate limit exceeded")

class ApiKeyService(
    private val teamRepository: TeamRepository,
    private val projectRepository: ProjectRepository,
    private val repository: ApiKeyRepository,
    private val auditLogService: AuditLogService,
    private val clock: Clock,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun create(
        request: CreateApiKeyRequest,
        actorId: String,
    ): CreatedApiKey {
        require(request.name.isNotBlank()) { "API key name must not be blank" }
        require(request.burstCapacity > 0) { "burstCapacity must be greater than zero" }
        require(request.refillTokensPerSecond > 0.0) { "refillTokensPerSecond must be greater than zero" }
        require(teamRepository.exists(request.teamId)) { "Unknown team '${request.teamId}'" }
        require(projectRepository.exists(request.teamId, request.projectId)) {
            "Unknown project '${request.projectId}' for team '${request.teamId}'"
        }
        val scopes = request.scopes.map(ApiKeyScope::fromWireName).toSet()
        require(scopes.isNotEmpty()) { "At least one API key scope must be provided" }

        val rawKey = buildRawKey()
        val record = ApiKeyRecord(
            id = randomId("key"),
            teamId = request.teamId,
            projectId = request.projectId,
            prefix = rawKey.take(API_KEY_LOOKUP_PREFIX_LENGTH),
            name = request.name,
            scopes = scopes,
            burstCapacity = request.burstCapacity,
            refillTokensPerSecond = request.refillTokensPerSecond,
            createdAt = clock.instant(),
            revokedAt = null,
            lastUsedAt = null,
        )
        repository.create(record, sha256Hex(rawKey))
        auditLogService.record(
            actorId = actorId,
            action = "api_key.create",
            resourceType = "api_key",
            resourceId = record.id,
            teamId = record.teamId,
            metadata = mapOf(
                "project_id" to record.projectId,
                "prefix" to record.prefix,
                "scopes" to record.scopes.map(ApiKeyScope::wireName).sorted(),
            ),
        )
        return CreatedApiKey(record = record, key = rawKey)
    }

    fun list(teamId: String, projectId: String): List<ApiKeyRecord> = repository.list(teamId, projectId)

    fun revoke(
        id: String,
        actorId: String,
    ): ApiKeyRecord {
        val revoked = repository.revoke(id, clock.instant())
            ?: throw IllegalArgumentException("API key '$id' was not found or already revoked")
        auditLogService.record(
            actorId = actorId,
            action = "api_key.revoke",
            resourceType = "api_key",
            resourceId = revoked.id,
            teamId = revoked.teamId,
            metadata = mapOf("project_id" to revoked.projectId, "prefix" to revoked.prefix),
        )
        return revoked
    }

    fun get(id: String): ApiKeyRecord? = repository.findById(id)

    private fun buildRawKey(): String = "tmr_" + randomToken(24)

    private fun randomId(prefix: String): String = "$prefix-${randomToken(12)}"

    private fun randomToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val API_KEY_LOOKUP_PREFIX_LENGTH = 16

        internal fun sha256Hex(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString(separator = "") { byte ->
                byte.toInt().and(0xff).toString(16).padStart(2, '0')
            }
    }
}

class ApiKeyAuthenticator(
    private val repository: ApiKeyRepository,
    private val clock: Clock,
) {
    fun authenticate(rawKey: String?): AuthenticatedApiKey {
        val candidate = rawKey?.trim().orEmpty()
        if (candidate.isBlank()) {
            throw AuthenticationException("An API key is required")
        }
        val stored = repository.findActiveByPrefix(candidate.take(16))
            ?: throw AuthenticationException("API key is invalid")
        if (ApiKeyService.sha256Hex(candidate) != stored.hashedKey) {
            throw AuthenticationException("API key is invalid")
        }
        repository.updateLastUsed(stored.record.id, clock.instant())
        return AuthenticatedApiKey(stored.record)
    }

    fun requireScope(
        principal: AuthenticatedApiKey,
        scope: ApiKeyScope,
    ) {
        if (!principal.hasScope(scope)) {
            throw AuthorizationException("API key '${principal.record.id}' does not grant scope '${scope.wireName}'")
        }
    }
}

class ApiKeyRateLimiter(
    private val clock: Clock,
) {
    private data class BucketState(
        var tokens: Double,
        var updatedAt: Instant,
    )

    private val buckets = ConcurrentHashMap<String, BucketState>()

    fun check(record: ApiKeyRecord): RateLimitDecision {
        val now = clock.instant()
        val state = buckets.compute(record.id) { _, existing ->
            val current = existing ?: BucketState(record.burstCapacity.toDouble(), now)
            val elapsedMillis = now.toEpochMilli() - current.updatedAt.toEpochMilli()
            if (elapsedMillis > 0) {
                val replenished = current.tokens + (elapsedMillis / 1000.0) * record.refillTokensPerSecond
                current.tokens = replenished.coerceAtMost(record.burstCapacity.toDouble())
                current.updatedAt = now
            }
            current
        } ?: error("Token bucket state was not created")

        return synchronized(state) {
            if (state.tokens >= 1.0) {
                state.tokens -= 1.0
                RateLimitDecision(
                    allowed = true,
                    limit = record.burstCapacity,
                    remaining = state.tokens.toInt(),
                    retryAfterSeconds = 0,
                    resetAtEpochSeconds = now.epochSecond,
                )
            } else {
                val secondsUntilNextToken = ((1.0 - state.tokens) / record.refillTokensPerSecond)
                    .coerceAtLeast(0.0)
                val retryAfterSeconds = kotlin.math.ceil(secondsUntilNextToken).toLong().coerceAtLeast(1)
                RateLimitDecision(
                    allowed = false,
                    limit = record.burstCapacity,
                    remaining = 0,
                    retryAfterSeconds = retryAfterSeconds,
                    resetAtEpochSeconds = now.plusSeconds(retryAfterSeconds).epochSecond,
                )
            }
        }
    }
}

internal fun ApiKeyRecord.toResponse(rawKey: String? = null): ApiKeyResponse = ApiKeyResponse(
    id = id,
    teamId = teamId,
    projectId = projectId,
    prefix = prefix,
    name = name,
    scopes = scopes.map(ApiKeyScope::wireName).sorted().toSet(),
    burstCapacity = burstCapacity,
    refillTokensPerSecond = refillTokensPerSecond,
    createdAt = createdAt,
    revokedAt = revokedAt,
    lastUsedAt = lastUsedAt,
    key = rawKey,
)
