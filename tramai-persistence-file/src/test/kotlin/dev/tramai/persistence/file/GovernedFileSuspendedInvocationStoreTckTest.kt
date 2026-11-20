package dev.tramai.persistence.file

import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.testing.persistence.engine.SuspendedInvocationStoreTck
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
 * 0.7.1d: GovernedFileSuspendedInvocationStore must satisfy the same
 * SuspendedInvocationStore compatibility contract as the store it composes.
 *
 * The governed capability is a wrapper, so enrolling it means proving the
 * delegation is transparent for the whole released contract — not just for the
 * governed paths. The runner owns the key, layout, and per-case isolation
 * exactly like the plain store's runner; only the returned type differs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernedFileSuspendedInvocationStoreTckTest : SuspendedInvocationStoreTck() {
    private val rootDir: Path = Files.createTempDirectory("tramai-governed-suspended-tck-").toAbsolutePath()
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
        return GovernedFileSuspendedInvocationStore(
            FileSuspendedInvocationStore(
                root = caseDir,
                key = testKey,
                configuration =
                    FileBackedStoreConfiguration(
                        rootDirectory = caseDir,
                        encryption =
                            FileStoreEncryptionConfiguration(
                                activeKeyId = "tck-key-1",
                                keyProvider = keyProvider,
                            ),
                        verifyOnOpen = false,
                    ),
                lease = FileStoreLease(),
            ),
        )
    }

    @AfterAll
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }
}
