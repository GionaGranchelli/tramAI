@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import dev.tramai.testing.persistence.outbox.SovereignOpsAuditOutboxStoreTck
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.path.exists

/**
 * 0.7.1d: GovernedFileSovereignOpsAuditOutboxStore is a concrete SovereignOpsAuditOutboxStore
 * implementation, so it must satisfy the shared outbox compatibility contract like any other store.
 *
 * Enrolling the wrapper is worth more than appeasing the guard: the released contract now runs through
 * the delegated layer, which is what proves legacy behaviour stays transparent rather than merely
 * claiming it in KDoc.
 *
 * The runner owns the encryption key and per-case isolation exactly like the plain store's runner.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernedFileSovereignOpsAuditOutboxStoreTckTest : SovereignOpsAuditOutboxStoreTck() {
    private val rootDir: Path = Files.createTempDirectory("tramai-governed-outbox-tck-").toAbsolutePath()
    private val testKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caseCounter = AtomicLong(0)

    override fun createStore(): SovereignOpsAuditOutboxStore =
        GovernedFileSovereignOpsAuditOutboxStore(
            FileSovereignOpsAuditOutboxStore(
                root = Files.createDirectories(rootDir.resolve("case-${caseCounter.incrementAndGet()}")),
                key = testKey,
            ),
        )

    @AfterAll
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }
}
