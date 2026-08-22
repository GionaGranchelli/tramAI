package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.testing.persistence.approval.ApprovalStoreTck
import dev.tramai.testing.persistence.approval.ApprovalStoreTckHarness
import dev.tramai.testing.persistence.approval.MutableClock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Epic 8.1a: FileApprovalStore must satisfy the shared ApprovalStore
 * compatibility contract (tramai-testing testFixtures). The runner owns the
 * temp directory, encryption key, and store construction — storage
 * technology never contaminates the contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileApprovalStoreTckTest : ApprovalStoreTck() {

    private lateinit var rootDir: Path
    private lateinit var key: SecretKey
    private val keyProvider = FileStoreEncryptionKeyProvider { key }

    @BeforeAll
    fun setUpAll() {
        rootDir = Files.createTempDirectory("tramai-approval-tck-").toAbsolutePath()
        Files.createDirectories(rootDir.resolve("approvals"))
        Files.setPosixFilePermissions(
            rootDir.resolve("approvals"),
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
        )
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    @AfterAll
    fun tearDownAll() {
        if (rootDir.toFile().exists()) rootDir.toFile().deleteRecursively()
    }

    private fun createConfig() = FileBackedStoreConfiguration(
        rootDirectory = rootDir,
        encryption = FileStoreEncryptionConfiguration(
            activeKeyId = "tck-key",
            keyProvider = keyProvider,
        ),
        verifyOnOpen = false,
    )

    override val harness = object : ApprovalStoreTckHarness {
        override fun createStore(clock: MutableClock): ApprovalStore =
            FileApprovalStore(rootDir, key, createConfig(), FileStoreLease(), clock)
    }
}
