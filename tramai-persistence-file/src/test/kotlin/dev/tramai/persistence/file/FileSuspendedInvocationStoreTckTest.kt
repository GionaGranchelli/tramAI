package dev.tramai.persistence.file

import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.testing.persistence.engine.SuspendedInvocationStoreTck
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
 * Epic 8.1c: FileSuspendedInvocationStore must satisfy the shared
 * SuspendedInvocationStore compatibility contract (tramai-testing
 * testFixtures). The runner owns the encryption key, directory layout, and
 * per-case isolation — storage technology never contaminates the contract.
 *
 * Each [createStore] call gets a fresh `case-N/suspended` directory so
 * previous cases' records cannot leak into the next case.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileSuspendedInvocationStoreTckTest : SuspendedInvocationStoreTck() {

    private val rootDir: Path = Files.createTempDirectory("tramai-suspended-tck-").toAbsolutePath()
    private val testKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val keyProvider = FileStoreEncryptionKeyProvider { testKey }
    private val caseCounter = AtomicLong(0)

    override fun createStore(): SuspendedInvocationStore {
        val caseDir = Files.createDirectories(rootDir.resolve("case-${caseCounter.incrementAndGet()}"))
        Files.createDirectories(caseDir.resolve("suspended"))
        Files.setPosixFilePermissions(
            caseDir.resolve("suspended"),
            PosixFilePermissions.fromString("rwx------"),
        )
        return FileSuspendedInvocationStore(
            root = caseDir,
            key = testKey,
            configuration = FileBackedStoreConfiguration(
                rootDirectory = caseDir,
                encryption = FileStoreEncryptionConfiguration(
                    activeKeyId = "tck-key-1",
                    keyProvider = keyProvider,
                ),
                verifyOnOpen = false,
            ),
            lease = FileStoreLease(),
        )
    }

    @AfterAll
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }
}
