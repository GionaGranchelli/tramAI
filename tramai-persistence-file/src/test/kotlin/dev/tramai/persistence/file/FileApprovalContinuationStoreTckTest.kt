package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.testing.persistence.approval.MutableClock
import dev.tramai.testing.persistence.approval.continuation.ApprovalContinuationStoreTck
import dev.tramai.testing.persistence.approval.continuation.ApprovalContinuationStoreTckHarness
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Epic 8.1b: FileApprovalContinuationStore must satisfy the shared
 * ApprovalContinuationStore compatibility contract (tramai-testing
 * testFixtures). The runner owns the temp directory, encryption key, and
 * store construction — storage technology never contaminates the contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileApprovalContinuationStoreTckTest : ApprovalContinuationStoreTck() {

    private lateinit var rootDir: Path
    private lateinit var key: SecretKey
    private val keyProvider = FileStoreEncryptionKeyProvider { key }
    private var caseCounter = 0

    @BeforeAll
    fun setUpAll() {
        rootDir = Files.createTempDirectory("tramai-continuation-tck-").toAbsolutePath()
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    @AfterAll
    fun tearDownAll() {
        if (rootDir.toFile().exists()) rootDir.toFile().deleteRecursively()
    }

    override val harness = object : ApprovalContinuationStoreTckHarness {
        override fun createStore(clock: MutableClock): ApprovalContinuationStore {
            // Fresh isolated storage per case: a unique child directory, so
            // previous cases' records never leak into the next case. The
            // @AfterAll cleanup removes the whole tree.
            val caseDir = rootDir.resolve("case-${caseCounter++}")
            Files.createDirectories(caseDir.resolve("continuations"))
            Files.setPosixFilePermissions(
                caseDir.resolve("continuations"),
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
            )
            val config = FileBackedStoreConfiguration(
                rootDirectory = caseDir,
                encryption = FileStoreEncryptionConfiguration(
                    activeKeyId = "tck-key",
                    keyProvider = keyProvider,
                ),
                verifyOnOpen = false,
            )
            return FileApprovalContinuationStore(caseDir, key, config, FileStoreLease(), clock)
        }
    }
}
