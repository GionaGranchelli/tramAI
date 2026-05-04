package dev.tramai.server

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AuditLogStoreTest {
    @Test
    fun `supports concurrent audit writes`() {
        val store = InMemoryAuditLogStore(maxEntries = 1_000)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val failures = AtomicReference<Throwable?>()

        repeat(120) { index ->
            executor.submit {
                try {
                    start.await()
                    store.recordAudit(
                        AuditRecord(
                            timestamp = Instant.parse("2026-05-04T12:00:00Z").plusSeconds(index.toLong()),
                            actor = "actor-${index % 3}",
                            action = "action-${index % 5}",
                            resourceType = "workflow",
                            resourceId = "wf-$index",
                            status = "ok",
                            metadata = mapOf("index" to index.toString()),
                        ),
                    )
                } catch (error: Throwable) {
                    failures.compareAndSet(null, error)
                }
            }
        }

        start.countDown()
        executor.shutdown()
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        assertThat(failures.get()).isNull()
        assertThat(store.query(size = 200).totalCount).isEqualTo(120)
    }

    @Test
    fun `evicts oldest entries when capacity is exceeded`() {
        val store = InMemoryAuditLogStore(maxEntries = 3)

        repeat(4) { index ->
            store.recordAudit(
                AuditRecord(
                    timestamp = Instant.parse("2026-05-04T12:00:00Z").plusSeconds(index.toLong()),
                    actor = "admin",
                    action = "workflow.run",
                    resourceType = "workflow",
                    resourceId = "wf-$index",
                    status = "ok",
                    metadata = emptyMap(),
                ),
            )
        }

        val result = store.query(size = 10)
        assertThat(result.totalCount).isEqualTo(3)
        assertThat(result.entries.map { it.resourceId }).containsExactly("wf-1", "wf-2", "wf-3")
    }

    @Test
    fun `filters audit queries by actor action and time range`() {
        val store = InMemoryAuditLogStore(maxEntries = 10)
        val base = Instant.parse("2026-05-04T12:00:00Z")

        store.recordAudit(
            AuditRecord(
                timestamp = base,
                actor = "alice",
                action = "workflow.run",
                resourceType = "workflow",
                resourceId = "wf-1",
                status = "ok",
                metadata = emptyMap(),
            ),
        )
        store.recordAudit(
            AuditRecord(
                timestamp = base.plusSeconds(60),
                actor = "bob",
                action = "workflow.run",
                resourceType = "workflow",
                resourceId = "wf-2",
                status = "ok",
                metadata = emptyMap(),
            ),
        )
        store.recordAudit(
            AuditRecord(
                timestamp = base.plusSeconds(120),
                actor = "alice",
                action = "schedule.tick",
                resourceType = "schedule",
                resourceId = "sch-1",
                status = "ok",
                metadata = emptyMap(),
            ),
        )

        val actorAndAction = store.query(actor = "alice", action = "workflow.run", size = 10)
        assertThat(actorAndAction.entries.map { it.resourceId }).containsExactly("wf-1")

        val timeRange = store.query(
            from = base.plusSeconds(30),
            to = base.plusSeconds(90),
            size = 10,
        )
        assertThat(timeRange.entries.map { it.resourceId }).containsExactly("wf-2")
    }
}
