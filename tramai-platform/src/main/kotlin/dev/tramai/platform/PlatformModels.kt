package dev.tramai.platform

import com.fasterxml.jackson.annotation.JsonValue
import dev.tramai.orchestration.ExternalStepExecutorFactory
import java.time.Instant

data class Team(
    val id: String,
    val name: String,
)

data class Project(
    val id: String,
    val teamId: String,
    val name: String,
)

enum class ApiKeyScope(
    val wireName: String,
) {
    RUN("run"),
    READ("read"),
    ADMIN("admin"),
    ;

    fun grants(required: ApiKeyScope): Boolean = this == ADMIN || this == required

    companion object {
        fun fromWireName(value: String): ApiKeyScope = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unsupported API key scope '$value'")
    }
}

data class ApiKeyRecord(
    val id: String,
    val teamId: String,
    val projectId: String,
    val prefix: String,
    val name: String,
    val scopes: Set<ApiKeyScope>,
    val burstCapacity: Int,
    val refillTokensPerSecond: Double,
    val createdAt: Instant,
    val revokedAt: Instant?,
    val lastUsedAt: Instant?,
)

data class CreatedApiKey(
    val record: ApiKeyRecord,
    val key: String,
)

data class AuditLogEntry(
    val id: Long,
    val timestamp: Instant,
    val actorId: String,
    val action: String,
    val resourceType: String,
    val resourceId: String,
    val teamId: String,
    val metadata: Map<String, Any?>,
)

interface WebhookAdapterFactory {
    val sourceId: String
    fun create(): WebhookAdapter
}

fun interface WebhookAdapter {
    suspend fun adapt(
        payload: String,
        headers: Map<String, String>,
    ): Map<String, Any?>
}

data class DashboardExtension(
    val id: String,
    val title: String,
)

interface TramaiPlugin {
    val id: String
    val version: String
    fun stepExecutors(): List<ExternalStepExecutorFactory>
    fun webhookAdapters(): List<WebhookAdapterFactory>
    fun dashboardExtensions(): List<DashboardExtension>
}

enum class PluginStatus(
    val wireName: String,
) {
    ENABLED("enabled"),
    DISABLED("disabled"),
    ERROR("error"),
    ;

    @JsonValue
    fun toJson(): String = wireName
}

data class PluginView(
    val id: String,
    val version: String,
    val status: PluginStatus,
    val jarPath: String,
    val error: String?,
    val stepTypes: List<String>,
    val webhookSources: List<String>,
    val dashboardExtensions: List<String>,
)

data class AuthenticatedApiKey(
    val record: ApiKeyRecord,
) {
    val actorId: String get() = record.id
    val teamId: String get() = record.teamId
    val projectId: String get() = record.projectId

    fun hasScope(required: ApiKeyScope): Boolean = record.scopes.any { it.grants(required) }
}
