package dev.tramai.persistence.file

import dev.tramai.security.audit.AuditStore
import dev.tramai.testing.persistence.audit.AuditStoreTck
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import kotlin.io.path.exists

/**
 * Epic 8.1d: FileAuditStore must satisfy the shared AuditStore compatibility
 * contract (tramai-testing testFixtures). The runner owns the encryption key
 * and per-case isolation — storage technology (encryption, permissions,
 * corruption handling) never contaminates the contract.
 *
 * Each [createStore] call gets a fresh `case-N` root directory; the store
 * provisions its own `audit/` layout.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileAuditStoreTckTest : AuditStoreTck() {

    private val rootDir: Path = Files.createTempDirectory("tramai-audit-tck-").toAbsolutePath()
    private val testKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val keyProvider = FileStoreEncryptionKeyProvider { testKey }
    private val caseCounter = AtomicLong(0)

    override fun createStore(): AuditStore {
        val caseDir = Files.createDirectories(rootDir.resolve("case-${caseCounter.incrementAndGet()}"))
        // The store expects a pre-provisioned managed audit/ directory with
        // 0700 permissions (the runner owns storage layout; the contract
        // never tests it).
        val auditDir = Files.createDirectories(caseDir.resolve("audit"))
        Files.setPosixFilePermissions(auditDir, PosixFilePermissions.fromString("rwx------"))
        return FileAuditStore(
            caseDir,
            testKey,
            FileBackedStoreConfiguration(
                rootDirectory = caseDir,
                encryption = FileStoreEncryptionConfiguration(
                    activeKeyId = "tck-key-1",
                    keyProvider = keyProvider,
                ),
                verifyOnOpen = false,
            ),
            FileStoreLease(),
        )
    }

    @AfterAll
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }
}
