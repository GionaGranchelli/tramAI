package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.testing.persistence.approval.ApprovalStoreTck
import dev.tramai.testing.persistence.approval.ApprovalStoreTckHarness
import dev.tramai.testing.persistence.approval.MutableClock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.path.exists

/**
 * 0.7.1d1: GovernedFileApprovalStore must satisfy the same ApprovalStore compatibility contract as
 * the store it composes.
 *
 * The governed capability is a wrapper, so enrolling it proves the delegation is transparent for the
 * whole released contract — not merely for the governed paths. Attribution persistence itself is
 * proven separately by FileGovernedApprovalAttributionTest; this runner owns the key, layout and
 * per-case isolation exactly like the plain store's runner, and only the returned type differs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernedFileApprovalStoreTckTest : ApprovalStoreTck() {
    private val rootDir: Path = Files.createTempDirectory("tramai-governed-approval-tck-").toAbsolutePath()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val keyProvider = FileStoreEncryptionKeyProvider { key }
    private val caseCounter = AtomicLong(0)

    override val harness =
        object : ApprovalStoreTckHarness {
            override fun createStore(clock: MutableClock): ApprovalStore {
                // Fresh isolated storage per case: previous cases' records never leak forward.
                val caseDir = rootDir.resolve("case-${caseCounter.incrementAndGet()}")
                Files.createDirectories(caseDir.resolve("approvals"))
                Files.setPosixFilePermissions(
                    caseDir.resolve("approvals"),
                    PosixFilePermissions.fromString("rwx------"),
                )
                val config =
                    FileBackedStoreConfiguration(
                        rootDirectory = caseDir,
                        encryption =
                            FileStoreEncryptionConfiguration(activeKeyId = "tck-key", keyProvider = keyProvider),
                        verifyOnOpen = false,
                    )
                return GovernedFileApprovalStore(FileApprovalStore(caseDir, key, config, FileStoreLease(), clock))
            }
        }

    @AfterAll
    fun tearDownAll() {
        if (rootDir.exists()) rootDir.toFile().deleteRecursively()
    }
}
