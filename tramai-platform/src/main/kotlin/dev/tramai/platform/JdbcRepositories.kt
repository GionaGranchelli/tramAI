package dev.tramai.platform

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

internal data class StoredApiKey(
    val record: ApiKeyRecord,
    val hashedKey: String,
)

internal data class PluginStateRecord(
    val id: String,
    val version: String,
    val jarPath: String,
    val enabled: Boolean,
    val status: PluginStatus,
    val error: String?,
)

class TeamRepository(
    private val dataSource: DataSource,
) {
    fun create(team: Team): Team {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into platform_team(id, name)
                values (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, team.id)
                statement.setString(2, team.name)
                statement.executeUpdate()
            }
        }
        return team
    }

    fun exists(teamId: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("select 1 from platform_team where id = ?").use { statement ->
            statement.setString(1, teamId)
            statement.executeQuery().use(ResultSet::next)
        }
    }
}

class ProjectRepository(
    private val dataSource: DataSource,
) {
    fun create(project: Project): Project {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into platform_project(id, team_id, name)
                values (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, project.id)
                statement.setString(2, project.teamId)
                statement.setString(3, project.name)
                statement.executeUpdate()
            }
        }
        return project
    }

    fun exists(teamId: String, projectId: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            select 1
            from platform_project
            where id = ? and team_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, projectId)
            statement.setString(2, teamId)
            statement.executeQuery().use(ResultSet::next)
        }
    }
}

class ApiKeyRepository(
    private val dataSource: DataSource,
) {
    fun create(
        record: ApiKeyRecord,
        hashedKey: String,
    ): ApiKeyRecord {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into platform_api_key(
                    id,
                    team_id,
                    project_id,
                    prefix,
                    hashed_key,
                    name,
                    scopes,
                    burst_capacity,
                    refill_tokens_per_second,
                    created_at,
                    revoked_at,
                    last_used_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, record.id)
                statement.setString(2, record.teamId)
                statement.setString(3, record.projectId)
                statement.setString(4, record.prefix)
                statement.setString(5, hashedKey)
                statement.setString(6, record.name)
                statement.setString(7, encodeScopes(record.scopes))
                statement.setInt(8, record.burstCapacity)
                statement.setDouble(9, record.refillTokensPerSecond)
                statement.setTimestamp(10, Timestamp.from(record.createdAt))
                statement.setTimestamp(11, record.revokedAt?.let(Timestamp::from))
                statement.setTimestamp(12, record.lastUsedAt?.let(Timestamp::from))
                statement.executeUpdate()
            }
        }
        return record
    }

    internal fun findActiveByPrefix(prefix: String): StoredApiKey? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            select *
            from platform_api_key
            where prefix = ? and revoked_at is null
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, prefix)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    null
                } else {
                    StoredApiKey(
                        record = result.toApiKeyRecord(),
                        hashedKey = result.getString("hashed_key"),
                    )
                }
            }
        }
    }

    fun findById(id: String): ApiKeyRecord? = dataSource.connection.use { connection ->
        connection.prepareStatement("select * from platform_api_key where id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                if (result.next()) result.toApiKeyRecord() else null
            }
        }
    }

    fun list(teamId: String, projectId: String): List<ApiKeyRecord> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            select *
            from platform_api_key
            where team_id = ? and project_id = ?
            order by created_at desc
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, teamId)
            statement.setString(2, projectId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(result.toApiKeyRecord())
                    }
                }
            }
        }
    }

    fun revoke(id: String, revokedAt: Instant): ApiKeyRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                update platform_api_key
                set revoked_at = ?
                where id = ? and revoked_at is null
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(revokedAt))
                statement.setString(2, id)
                statement.executeUpdate()
            }
        }
        return findById(id)
    }

    fun updateLastUsed(id: String, lastUsedAt: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                update platform_api_key
                set last_used_at = ?
                where id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(lastUsedAt))
                statement.setString(2, id)
                statement.executeUpdate()
            }
        }
    }

    private fun ResultSet.toApiKeyRecord(): ApiKeyRecord = ApiKeyRecord(
        id = getString("id"),
        teamId = getString("team_id"),
        projectId = getString("project_id"),
        prefix = getString("prefix"),
        name = getString("name"),
        scopes = decodeScopes(getString("scopes")),
        burstCapacity = getInt("burst_capacity"),
        refillTokensPerSecond = getDouble("refill_tokens_per_second"),
        createdAt = getTimestamp("created_at").toInstant(),
        revokedAt = getTimestamp("revoked_at")?.toInstant(),
        lastUsedAt = getTimestamp("last_used_at")?.toInstant(),
    )

    private fun encodeScopes(scopes: Set<ApiKeyScope>): String =
        scopes.map(ApiKeyScope::wireName).sorted().joinToString(",")

    private fun decodeScopes(serialized: String): Set<ApiKeyScope> =
        serialized.split(',')
            .filter(String::isNotBlank)
            .map(ApiKeyScope::fromWireName)
            .toSet()
}

class AuditLogRepository(
    private val dataSource: DataSource,
    private val objectMapper: ObjectMapper,
) {
    fun append(entry: AuditLogEntry) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into platform_audit_log(
                    timestamp,
                    actor_id,
                    action,
                    resource_type,
                    resource_id,
                    team_id,
                    metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(entry.timestamp))
                statement.setString(2, entry.actorId)
                statement.setString(3, entry.action)
                statement.setString(4, entry.resourceType)
                statement.setString(5, entry.resourceId)
                statement.setString(6, entry.teamId)
                statement.setString(7, objectMapper.writeValueAsString(entry.metadata))
                statement.executeUpdate()
            }
        }
    }

    fun list(
        teamId: String,
        action: String?,
    ): List<AuditLogEntry> = dataSource.connection.use { connection ->
        val sql = buildString {
            append(
                """
                select id, timestamp, actor_id, action, resource_type, resource_id, team_id, metadata_json
                from platform_audit_log
                where team_id = ?
                """.trimIndent(),
            )
            if (!action.isNullOrBlank()) {
                append(" and action = ?")
            }
            append(" order by timestamp desc, id desc")
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, teamId)
            if (!action.isNullOrBlank()) {
                statement.setString(2, action)
            }
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            AuditLogEntry(
                                id = result.getLong("id"),
                                timestamp = result.getTimestamp("timestamp").toInstant(),
                                actorId = result.getString("actor_id"),
                                action = result.getString("action"),
                                resourceType = result.getString("resource_type"),
                                resourceId = result.getString("resource_id"),
                                teamId = result.getString("team_id"),
                                metadata = objectMapper.readValue(
                                    result.getString("metadata_json"),
                                    object : TypeReference<Map<String, Any?>>() {},
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

class PluginStateRepository(
    private val dataSource: DataSource,
) {
    internal fun find(id: String): PluginStateRecord? = dataSource.connection.use { connection ->
        connection.prepareStatement("select * from platform_plugin where id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                if (!result.next()) null else result.toPluginState()
            }
        }
    }

    internal fun upsert(record: PluginStateRecord) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                merge into platform_plugin key(id)
                values (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, record.id)
                statement.setString(2, record.version)
                statement.setString(3, record.jarPath)
                statement.setBoolean(4, record.enabled)
                statement.setString(5, record.status.wireName)
                statement.setString(6, record.error)
                statement.setTimestamp(7, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                update platform_plugin
                set enabled = ?, status = ?, error = null, updated_at = ?
                where id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setBoolean(1, enabled)
                statement.setString(2, if (enabled) PluginStatus.ENABLED.wireName else PluginStatus.DISABLED.wireName)
                statement.setTimestamp(3, Timestamp.from(Instant.now()))
                statement.setString(4, id)
                statement.executeUpdate()
            }
        }
    }

    private fun ResultSet.toPluginState(): PluginStateRecord = PluginStateRecord(
        id = getString("id"),
        version = getString("version"),
        jarPath = getString("jar_path"),
        enabled = getBoolean("enabled"),
        status = PluginStatus.entries.first { it.wireName == getString("status") },
        error = getString("error"),
    )
}

class AuditLogService(
    private val repository: AuditLogRepository,
    private val clock: Clock,
) {
    fun record(
        actorId: String,
        action: String,
        resourceType: String,
        resourceId: String,
        teamId: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        repository.append(
            AuditLogEntry(
                id = 0,
                timestamp = clock.instant(),
                actorId = actorId,
                action = action,
                resourceType = resourceType,
                resourceId = resourceId,
                teamId = teamId,
                metadata = metadata,
            ),
        )
    }

    fun list(teamId: String, action: String?): List<AuditLogEntry> = repository.list(teamId, action)
}
