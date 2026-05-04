package dev.tramai.server

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class AuditController(
    private val auditLogStore: InMemoryAuditLogStore,
) {
    @GetMapping("/audit")
    fun queryAuditLog(
        @RequestParam(required = false) actor: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): Map<String, Any> {
        val fromInstant = from?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val toInstant = to?.let { runCatching { Instant.parse(it) }.getOrNull() }

        val result = auditLogStore.query(
            actor = actor,
            action = action,
            from = fromInstant,
            to = toInstant,
            page = page,
            size = size,
        )

        return mapOf(
            "entries" to result.entries.map { record ->
                mapOf(
                    "timestamp" to record.timestamp.toString(),
                    "actor" to record.actor,
                    "action" to record.action,
                    "resourceType" to record.resourceType,
                    "resourceId" to record.resourceId,
                    "status" to record.status,
                    "metadata" to record.metadata,
                )
            },
            "totalCount" to result.totalCount,
            "page" to result.page,
            "pageSize" to result.pageSize,
        )
    }
}
