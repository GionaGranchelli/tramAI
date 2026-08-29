package dev.tramai.orchestration

import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.3d — process-wait monotonic injection.
 *
 * The bounded process waits must read their deadline from the injectable
 * monotonic source (defaulting to the system source at the composition
 * boundary). An injected clock that advances past the deadline on the first
 * read terminates the wait immediately — zero real sleeping, fully
 * deterministic.
 */
class ProcessSupportMonotonicDiscriminatorTest {

    @Test
    fun `waitForBounded returns null when the injected monotonic deadline already expired`() {
        // Long-running child: it is still alive when the injected deadline expires,
        // so the wait terminates immediately with null (no real sleeping, no exit value).
        val process = ProcessBuilder("sh", "-c", "sleep 30").start()
        val clock = AtomicLong(0L)

        // Deadline read advances the fake clock to 1s (deadline = 1s + 1s timeout);
        // the first poll read advances it to 2s, which is not < the deadline, so the
        // loop exits on the very first check without sleeping or waiting.
        val result = process.waitForBounded(timeoutMillis = 1_000) { clock.addAndGet(1_000_000_000L) }

        assertThat(result).isNull()
        assertThat(clock.get()).isEqualTo(2_000_000_000L)
        process.destroyForcibly()
    }
}
