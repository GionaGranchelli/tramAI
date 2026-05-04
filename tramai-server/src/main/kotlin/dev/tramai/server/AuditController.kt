package dev.tramai.server

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@ConditionalOnProperty(prefix = "tramai.dashboard", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AuditController(
    private val auditLogStore: InMemoryAuditLogStore,
) {
    @GetMapping("/audit")
    fun queryAuditLog(
        @RequestParam(required = false) actor: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        from: Instant?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        to: Instant?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): AuditPage {
        require(page >= 0) { "page must be greater than or equal to zero" }
        require(size in 1..100) { "size must be between 1 and 100" }

        val result = auditLogStore.query(
            actor = actor,
            action = action,
            from = from,
            to = to,
            page = page,
            size = size,
        )

        return AuditPage(
            entries = result.entries,
            totalCount = result.totalCount,
            page = result.page,
            pageSize = result.pageSize,
        )
    }
}

data class AuditPage(
    val entries: List<AuditRecord>,
    val totalCount: Long,
    val page: Int,
    val pageSize: Int,
)
