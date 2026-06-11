package dev.tramai.persistence.file

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.path.*
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileBackedSovereignStoresTest {

    private val rootDir: Path = Files.createTempDirectory("tramai-bundle-test-").toAbsolutePath()

    private fun testKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val testKey: SecretKey by lazy { testKey() }

    private val keyProvider = FileStoreEncryptionKeyProvider { testKey }

    private fun createConfig(root: Path = rootDir) = FileBackedStoreConfiguration(
        rootDirectory = root,
        encryption = FileStoreEncryptionConfiguration(
            activeKeyId = "test-key",
            keyProvider = keyProvider,
        ),
        verifyOnOpen = false,
    )

    @AfterEach
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `open creates root directory with 0700 permissions`() {
        // Use a new subdirectory that doesn't exist yet
        val newRoot = rootDir.resolve("fresh-root")
        val config = createConfig(newRoot)

        val stores = FileBackedSovereignStores.open(config)
        stores.close()

        assertTrue(newRoot.exists(), "Root directory must exist after open")
        assertTrue(newRoot.isDirectory(), "Root must be a directory")

        val perms = Files.getPosixFilePermissions(newRoot)
        assertTrue(perms.containsAll(
            PosixFilePermissions.fromString("rwx------"),
        ), "Permissions must be 0700")
    }

    @Test
    fun `open creates manifest json`() {
        val config = createConfig()
        val stores = FileBackedSovereignStores.open(config)
        stores.close()

        val manifestPath = rootDir.resolve("manifest.json")
        assertTrue(manifestPath.exists(), "manifest.json must exist")

        val manifestJson = manifestPath.readText()
        val manifest = StoreManifestV1.fromJson(manifestJson)
        assertTrue(manifest.createdAt.isNotBlank())
    }

    @Test
    fun `open acquires exclusive lock on tramai lock`() {
        val config = createConfig()
        val stores = FileBackedSovereignStores.open(config)

        try {
            val lockPath = rootDir.resolve(".tramai.lock")
            assertTrue(lockPath.exists(), "Lock file must exist")
        } finally {
            stores.close()
        }
    }

    @Test
    fun `second open for same root is rejected`() {
        val config = createConfig()
        val stores1 = FileBackedSovereignStores.open(config)

        try {
            // On the same JVM, tryLock() on the same file throws OverlappingFileLockException
            // rather than FileStoreLockUnavailableException
            assertThrows<Exception> {
                FileBackedSovereignStores.open(config)
            }
        } finally {
            stores1.close()
        }
    }

    @Test
    fun `close releases lock`() {
        val config = createConfig()
        val stores1 = FileBackedSovereignStores.open(config)
        stores1.close()

        // Should succeed now that the lock is released
        val stores2 = FileBackedSovereignStores.open(config)
        stores2.close()
    }

    @Test
    fun `open rejects symlink root`() {
        val realRoot = rootDir.resolve("real-dir")
        Files.createDirectories(realRoot)

        val symlinkRoot = rootDir.resolve("link-to-root")
        Files.createSymbolicLink(symlinkRoot, realRoot)

        val config = createConfig(symlinkRoot)

        assertThrows<IllegalArgumentException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `stores are accessible after open`() {
        val config = createConfig()
        val stores = FileBackedSovereignStores.open(config)

        try {
            assertNotNull(stores.approvalStore, "approvalStore must be non-null")
            assertNotNull(stores.approvalContinuationStore, "approvalContinuationStore must be non-null")
            assertNotNull(stores.auditStore, "auditStore must be non-null")

            assertTrue(stores.approvalStore is FileApprovalStore)
            assertTrue(stores.approvalContinuationStore is FileApprovalContinuationStore)
            assertTrue(stores.auditStore is FileAuditStore)
        } finally {
            stores.close()
        }
    }

    @Test
    fun `open with verifyOnOpen succeeds on empty directory`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("verify-empty"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "test-key",
                keyProvider = keyProvider,
            ),
            verifyOnOpen = true,
        )
        val stores = FileBackedSovereignStores.open(config)
        stores.close()
    }

    @Test
    fun `close is idempotent`() {
        val config = createConfig()
        val stores = FileBackedSovereignStores.open(config)
        stores.close()
        // Second close should be a no-op
        stores.close()
    }
}
