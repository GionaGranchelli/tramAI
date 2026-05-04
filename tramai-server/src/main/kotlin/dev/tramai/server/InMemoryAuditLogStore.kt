package dev.tramai.server

import java.time.Instant

data class AuditRecord(
    val timestamp: Instant,
    val actor: String,
    val action: String,
    val resourceType: String,
    val resourceId: String,
    val status: String,
    val metadata: Map<String, String>,
)

class InMemoryAuditLogStore(
    private val maxEntries: Int = 10_000,
) {
    private val monitor = Any()
    private val entries = mutableListOf<AuditRecord>()

    fun recordAudit(record: AuditRecord) {
        synchronized(monitor) {
            entries.add(record)
            if (entries.size > maxEntries) {
                entries.removeAt(0)
            }
        }
    }

    data class AuditQueryResult(
        val entries: List<AuditRecord>,
        val totalCount: Long,
        val page: Int,
        val pageSize: Int,
    )

    fun query(
        actor: String? = null,
        action: String? = null,
        from: Instant? = null,
        to: Instant? = null,
        page: Int = 0,
        size: Int = 50,
    ): AuditQueryResult = synchronized(monitor) {
        val filtered = entries.asSequence()
            .filter { record ->
                (actor == null || record.actor == actor) &&
                    (action == null || record.action == action) &&
                    (from == null || !record.timestamp.isBefore(from)) &&
                    (to == null || !record.timestamp.isAfter(to))
            }
            .toList()

        val totalCount = filtered.size.toLong()
        val effectivePage = page.coerceAtLeast(0)
        val effectiveSize = size.coerceIn(1, 200)
        val paged = filtered
            .drop(effectivePage * effectiveSize)
            .take(effectiveSize)

        AuditQueryResult(
            entries = paged,
            totalCount = totalCount,
            page = effectivePage,
            pageSize = effectiveSize,
        )
    }
}
