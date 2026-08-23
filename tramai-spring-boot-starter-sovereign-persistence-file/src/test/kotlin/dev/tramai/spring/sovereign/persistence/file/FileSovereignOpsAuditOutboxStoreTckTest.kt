package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import dev.tramai.testing.persistence.outbox.SovereignOpsAuditOutboxStoreTck
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import kotlin.io.path.exists

/**
 * Epic 8.1e: FileSovereignOpsAuditOutboxStore must satisfy the shared outbox
 * compatibility contract (tramai-testing testFixtures). The runner owns the
 * encryption key and per-case isolation — storage technology (durability,
 * encryption format, permissions, corruption detection, record versions)
 * never contaminates the contract.
 *
 * Each [createStore] call gets a fresh `case-N` root directory; the store
 * provisions its own `outbox/` layout.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileSovereignOpsAuditOutboxStoreTckTest : SovereignOpsAuditOutboxStoreTck() {

    private val rootDir: Path = Files.createTempDirectory("tramai-outbox-tck-").toAbsolutePath()
    private val testKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caseCounter = AtomicLong(0)

    override fun createStore(): SovereignOpsAuditOutboxStore {
        val caseDir = Files.createDirectories(rootDir.resolve("case-${caseCounter.incrementAndGet()}"))
        return FileSovereignOpsAuditOutboxStore(root = caseDir, key = testKey)
    }

    @AfterAll
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }
}
