package dev.tramai.spring.sovereign.persistence.jdbc.inbox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
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
 * Inbox metadata fields (`requiredRole`, `riskLevel`, `subjectType`,
 * `subjectId`, `recommendationType`) are read from the `sanitized_metadata->'inbox'`
 * JSONB object persisted during transactional approval-request creation.
 * When inbox metadata is absent (legacy approvals), all metadata fields map to null.
 *
 * @param dataSource PostgreSQL DataSource
 * @param clock clock for temporal checks
 */
class JdbcApprovalInboxQueryService(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalInboxQueryService {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /**
     * Canonical `effective_expires_at` expression reused for filtering,
     * ordering, cursor construction, and the mapped [ApprovalInboxWorkItem].
     *
     * Precedence:
     * 1. `c.approval_expires_at` (continuation-level expiry, strongest signal)
     * 2. `sanitized_metadata->>'expiresAt'` (approval-level expiry, if present)
     * 3. `created_at + 5 minutes` (fallback so no row has an undefined expiry)
     */
    private val effectiveExpiresAtExpr = """
        COALESCE(
            c.approval_expires_at,
            NULLIF(a.sanitized_metadata->>'expiresAt', '')::timestamptz,
            a.created_at + INTERVAL '5 minutes'
        )
    """.trimIndent().replace('\n', ' ')

    private val baseSql = """
        SELECT a.approval_id,
               a.status,
               a.created_at,
               a.sanitized_metadata,
               a.version,
               c.status AS continuation_status,
               c.workflow_run_id,
               $effectiveExpiresAtExpr AS effective_expires_at,
               a.sanitized_metadata->'inbox'->>'requiredRole' AS inbox_required_role,
               a.sanitized_metadata->'inbox'->>'riskLevel' AS inbox_risk_level,
               a.sanitized_metadata->'inbox'->>'subjectType' AS inbox_subject_type,
               a.sanitized_metadata->'inbox'->>'subjectId' AS inbox_subject_id,
               a.sanitized_metadata->'inbox'->>'recommendationType' AS inbox_recommendation_type
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
                query.requiredRole?.let {
                    append(" AND a.sanitized_metadata->'inbox'->>'requiredRole' = ?")
                }
                query.requestedBy?.let { append(" AND a.sanitized_metadata->>'requestedBy' = ?") }
                if (query.expiresBefore != null) {
                    append(" AND $effectiveExpiresAtExpr <= ?")
                }
                if (cursor != null) {
                    append(
                        " AND ($effectiveExpiresAtExpr, a.sanitized_metadata->>'requestedAt', a.approval_id) > (?, ?, ?)",
                    )
                }
                append(
                    " ORDER BY $effectiveExpiresAtExpr ASC, " +
                        "a.sanitized_metadata->>'requestedAt' ASC, " +
                        "a.approval_id ASC",
                )
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
                    stmt.setString(idx++, cursor.requestedAt)
                    stmt.setString(idx++, cursor.approvalId)
                }
                stmt.setInt(idx, effectiveLimit + 1)

                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<InboxRow>()
                    while (rs.next()) {
                        rows += InboxRow(
                            rs.getString("approval_id"),
                            mapRow(rs),
                        )
                    }
                    val hasMore = rows.size > effectiveLimit
                    val selected = if (hasMore) rows.take(effectiveLimit) else rows
                    val nextCursor = if (hasMore) {
                        val item = selected.last()
                        encodeCursor(
                            InboxCursor(
                                expiresAt = item.item.expiresAt,
                                requestedAt = item.item.requestedAt.toString(),
                                approvalId = item.approvalId,
                            ),
                        )
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
        val requestedAtString = metadata.requestedAt
        val createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant()
        val effectiveRequestedAt = requestedAtString?.let(Instant::parse) ?: createdAt
        val effectiveExpiresAt = rs.getObject("effective_expires_at", OffsetDateTime::class.java).toInstant()
        val workflowRunId = rs.getString("workflow_run_id")
            ?: metadata.binding?.workflowRunId
            ?: error("workflowRunId missing for ${rs.getString("approval_id")}")

        // Read inbox metadata from JSONB columns — null when absent (legacy approvals)
        val inboxRequiredRole = rs.getString("inbox_required_role")
        val inboxRiskLevel = rs.getString("inbox_risk_level")
        val inboxSubjectType = rs.getString("inbox_subject_type")
        val inboxSubjectId = rs.getString("inbox_subject_id")
        val inboxRecommendationType = rs.getString("inbox_recommendation_type")

        return ApprovalInboxWorkItem(
            approvalId = ApprovalId(rs.getString("approval_id")),
            workflowRunId = workflowRunId,
            toolName = metadata.binding?.toolName ?: "unknown",
            status = ApprovalStatus.valueOf(rs.getString("status")),
            requestedBy = metadata.requestedBy ?: "unknown",
            requestedAt = effectiveRequestedAt,
            expiresAt = effectiveExpiresAt,
            requiredRole = inboxRequiredRole?.let { ApproverRole(it) },
            riskLevel = inboxRiskLevel,
            subjectType = inboxSubjectType,
            subjectId = inboxSubjectId,
            recommendationType = inboxRecommendationType,
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
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mapper.writeValueAsBytes(c))

    private fun decodeCursor(raw: String): InboxCursor =
        try {
            mapper.readValue<InboxCursor>(Base64.getUrlDecoder().decode(raw))
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid-approval-inbox-cursor", e)
        }

    private data class InboxRow(
        val approvalId: String,
        val item: ApprovalInboxWorkItem,
    )

    /**
     * Cursor tuple: encodes the last item's position for pagination.
     *
     * `expiresAt` is the [ApprovalInboxWorkItem.expiresAt] instant as an ISO string,
     * `requestedAt` is the item's [ApprovalInboxWorkItem.requestedAt] ISO string,
     * `approvalId` is the unique tiebreaker.
     */
    private data class InboxCursor(
        val expiresAt: Instant,
        val requestedAt: String,
        val approvalId: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class InboxBindingMetadata(
        val workflowRunId: String? = null,
        val toolName: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class InboxMetadata(
        val binding: InboxBindingMetadata? = null,
        val requestedBy: String? = null,
        val expiresAt: String? = null,
        val requestedAt: String? = null,
    )
}
