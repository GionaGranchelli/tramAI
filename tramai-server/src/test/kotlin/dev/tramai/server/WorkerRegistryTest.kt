package dev.tramai.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WorkerRegistryTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `supports concurrent registrations and heartbeats`() {
        val registry = InMemoryWorkerRegistry(objectMapper = objectMapper)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val failures = AtomicReference<Throwable?>()

        repeat(24) { index ->
            executor.submit {
                try {
                    start.await()
                    val workerId = "worker-$index"
                    registry.register(workerId, "default", setOf("etl"), "1.0.$index", "host-$index")
                    repeat(10) {
                        registry.heartbeat(workerId)
                    }
                } catch (error: Throwable) {
                    failures.compareAndSet(null, error)
                }
            }
        }

        start.countDown()
        executor.shutdown()
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        assertThat(failures.get()).isNull()

        val workers = registry.listWorkers()
        assertThat(workers).hasSize(24)
        assertThat(workers.map { it.workerId }).containsExactlyInAnyOrderElementsOf((0 until 24).map { "worker-$it" })
        assertThat(workers.map { it.status }.distinct()).containsOnly("online")
    }

    @Test
    fun `worker status transitions from online to stale to offline`() {
        val clock = MutableClock(Instant.parse("2026-05-04T12:00:00Z"))
        val registry = InMemoryWorkerRegistry(objectMapper = objectMapper, clock = clock)

        registry.register("worker-1", "default", emptySet(), "1.0.0", "host-1")
        assertThat(registry.listWorkers().single().status).isEqualTo("online")

        clock.advanceBy(Duration.ofSeconds(20))
        assertThat(registry.listWorkers().single().status).isEqualTo("stale")

        clock.advanceBy(Duration.ofSeconds(11))
        assertThat(registry.listWorkers().single().status).isEqualTo("offline")
    }

    @Test
    fun `registers sse emitters and cleans them up on completion`() {
        val registry = InMemoryWorkerRegistry(objectMapper = objectMapper)
        val completedEmitter = RecordingSseEmitter()
        val activeEmitter = RecordingSseEmitter()

        registry.registerSseEmitter(completedEmitter)
        registry.registerSseEmitter(activeEmitter)
        completedEmitter.complete()

        registry.register("worker-1", "default", setOf("etl"), "1.0.0", "host-1")

        assertThat(completedEmitter.sendCount).isEqualTo(1)
        assertThat(activeEmitter.sendCount).isEqualTo(2)
    }
}

private class RecordingSseEmitter : SseEmitter(300_000L) {
    var sendCount: Int = 0
        private set
    private var completed: Boolean = false

    override fun complete() {
        completed = true
        super.complete()
    }

    override fun send(event: SseEventBuilder) {
        if (completed) {
            throw IllegalStateException("Emitter already completed")
        }
        sendCount += 1
    }
}

private class MutableClock(
    private var currentInstant: Instant,
    private var currentZone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun getZone(): ZoneId = currentZone

    override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

    override fun instant(): Instant = currentInstant

    fun advanceBy(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}
