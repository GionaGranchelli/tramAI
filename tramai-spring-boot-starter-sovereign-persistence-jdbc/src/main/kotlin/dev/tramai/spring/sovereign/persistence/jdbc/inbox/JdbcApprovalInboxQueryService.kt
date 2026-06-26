package dev.tramai.spring.sovereign.persistence.jdbc.inbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxPage
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQuery
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQueryService
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxWorkItem
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import javax.sql.DataSource

/**
 * JDBC-backed approval inbox query service over the `approvals` and
 * `approval_continuations` tables.
 *
 * Reads only safe projections. Never returns resume tokens, token digests,
 * raw tool arguments, replay envelopes, or decision comments.
 *
 * @param dataSource PostgreSQL DataSource
 * @param clock clock for temporal checks
 */
class JdbcApprovalInboxQueryService(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalInboxQueryService {

    private val mapper = ObjectMapper().registerKotlinModule()

    private val baseSql = """
        SELECT a.approval_id,
               a.status,
               a.created_at,
               a.sanitized_metadata,
               a.version,
               c.status AS continuation_status,
               c.workflow_run_id,
               c.approval_expires_at
        FROM approvals a
        LEFT JOIN approval_continuations c ON a.approval_id = c.approval_id
    """.trimIndent()

    override suspend fun search(query: ApprovalInboxQuery): ApprovalInboxPage =
        dataSource.connection.use { conn ->
            val effectiveLimit = query.limit.coerceIn(1, 100)
            val cursor = query.cursor?.let(::decodeCursor)
            val sql = buildString {
                append(baseSql)
                append(" WHERE 1=1")
                query.status?.let { append(" AND a.status = ?") }
                query.requiredRole?.let { append(" AND a.sanitized_metadata->>'requiredRole' = ?") }
                query.requestedBy?.let { append(" AND a.sanitized_metadata->>'requestedBy' = ?") }
                if (query.expiresBefore != null) {
                    append(
                        " AND COALESCE(c.approval_expires_at, " +
                            "NULLIF(a.sanitized_metadata->>'expiresAt', '')::timestamptz" +
                        ") <= ?",
                    )
                }
                if (cursor != null) {
                    append(" AND (COALESCE(c.approval_expires_at, a.created_at + INTERVAL '5 minutes'), a.created_at, a.approval_id) > (?, ?, ?)")
                }
                append(" ORDER BY COALESCE(c.approval_expires_at, a.created_at + INTERVAL '5 minutes') ASC, a.created_at ASC, a.approval_id ASC")
                append(" LIMIT ?")
            }

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                query.status?.let { stmt.setString(idx++, it.name) }
                query.requiredRole?.let { stmt.setString(idx++, it.value) }
                query.requestedBy?.let { stmt.setString(idx++, it) }
                if (query.expiresBefore != null) {
                    stmt.setObject(idx++, OffsetDateTime.ofInstant(query.expiresBefore, ZoneOffset.UTC))
                }
                if (cursor != null) {
                    stmt.setObject(idx++, OffsetDateTime.ofInstant(cursor.expiresAt, ZoneOffset.UTC))
                    stmt.setObject(idx++, OffsetDateTime.ofInstant(cursor.requestedAt, ZoneOffset.UTC))
                    stmt.setString(idx++, cursor.approvalId)
                }
                stmt.setInt(idx, effectiveLimit + 1)

                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<InboxRow>()
                    while (rs.next()) {
                        rows += InboxRow(
                            expiresAt = rs.getObject("approval_expires_at", OffsetDateTime::class.java)
                                ?.toInstant(),
                            requestedAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                            approvalId = rs.getString("approval_id"),
                            item = mapRow(rs),
                        )
                    }
                    val hasMore = rows.size > effectiveLimit
                    val selected = if (hasMore) rows.take(effectiveLimit) else rows
                    val nextCursor = if (hasMore) {
                        val item = selected.last()
                        encodeCursor(InboxCursor(
                            expiresAt = item.expiresAt ?: item.requestedAt.plusSeconds(300),
                            requestedAt = item.requestedAt,
                            approvalId = item.approvalId,
                        ))
                    } else null
                    ApprovalInboxPage(selected.map { it.item }, nextCursor)
                }
            }
        }

    override suspend fun getWorkItem(approvalId: ApprovalId): ApprovalInboxWorkItem? =
        dataSource.connection.use { conn ->
            val sql = "$baseSql WHERE a.approval_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, approvalId.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }

    private fun mapRow(rs: ResultSet): ApprovalInboxWorkItem {
        val metadata = parseMetadata(rs.getString("sanitized_metadata"))
        val createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant()
        val expiresAt = rs.getObject("approval_expires_at", OffsetDateTime::class.java)?.toInstant()
            ?: metadata.expiresAt?.let(Instant::parse)
            ?: createdAt.plusSeconds(300)
        val workflowRunId = rs.getString("workflow_run_id")
            ?: metadata.binding?.workflowRunId
            ?: error("workflowRunId missing for ${rs.getString("approval_id")}")

        return ApprovalInboxWorkItem(
            approvalId = ApprovalId(rs.getString("approval_id")),
            workflowRunId = workflowRunId,
            toolName = metadata.binding?.toolName ?: "unknown",
            status = ApprovalStatus.valueOf(rs.getString("status")),
            requestedBy = metadata.requestedBy ?: "unknown",
            requestedAt = createdAt,
            expiresAt = expiresAt,
            requiredRole = metadata.requiredRole?.let(::ApproverRole),
            riskLevel = metadata.riskLevel,
            subjectType = metadata.subjectType,
            subjectId = metadata.subjectId,
            recommendationType = metadata.recommendationType,
            continuationStatus = rs.getString("continuation_status")
                ?.let(ApprovalContinuationStatus::valueOf),
            version = rs.getLong("version"),
        )
    }

    private fun parseMetadata(json: String?): InboxMetadata {
        require(!json.isNullOrBlank()) { "sanitized_metadata must not be null" }
        return mapper.readValue(json)
    }

    private fun encodeCursor(c: InboxCursor): String =
        Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(c))

    private fun decodeCursor(raw: String): InboxCursor =
        try {
            mapper.readValue<InboxCursor>(Base64.getDecoder().decode(raw))
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid-approval-inbox-cursor", e)
        }

    private data class InboxRow(
        val expiresAt: Instant?,
        val requestedAt: Instant,
        val approvalId: String,
        val item: ApprovalInboxWorkItem,
    )

    private data class InboxCursor(
        val expiresAt: Instant,
        val requestedAt: Instant,
        val approvalId: String,
    )

    private data class InboxBindingMetadata(
        val workflowRunId: String? = null,
        val toolName: String? = null,
    )

    private data class InboxMetadata(
        val binding: InboxBindingMetadata? = null,
        val requestedBy: String? = null,
        val expiresAt: String? = null,
        val requiredRole: String? = null,
        val riskLevel: String? = null,
        val subjectType: String? = null,
        val subjectId: String? = null,
        val recommendationType: String? = null,
    )
}
