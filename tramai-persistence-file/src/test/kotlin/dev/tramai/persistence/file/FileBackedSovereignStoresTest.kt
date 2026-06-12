package dev.tramai.persistence.file

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
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
        val newRoot = rootDir.resolve("fresh-root")
        val config = createConfig(newRoot)

        val stores = FileBackedSovereignStores.open(config)
        stores.close()

        assertTrue(newRoot.exists(), "Root directory must exist after open")
        assertTrue(newRoot.isDirectory(), "Root must be a directory")

        val perms = Files.getPosixFilePermissions(newRoot)
        assertTrue(
            perms.containsAll(PosixFilePermissions.fromString("rwx------")),
            "Permissions must be 0700",
        )
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
    fun `open rejects root with wrong permissions`() {
        val badPermsRoot = rootDir.resolve("bad-perms-root")
        Files.createDirectories(badPermsRoot)
        Files.setPosixFilePermissions(badPermsRoot, PosixFilePermissions.fromString("rwxrwxrwx"))

        assertThrows<IllegalArgumentException> {
            FileBackedSovereignStores.open(createConfig(badPermsRoot))
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
        stores.close()
    }

    // ── activeKeyId validation ──────────────────────────────────────────

    @Test
    fun `open rejects blank activeKeyId`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("blank-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "",
                keyProvider = keyProvider,
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects whitespace-only activeKeyId`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("ws-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "   ",
                keyProvider = keyProvider,
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects overly long activeKeyId`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("long-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "x".repeat(129),
                keyProvider = keyProvider,
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects activeKeyId with unsafe pattern`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("unsafe-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "-starts-with-hyphen",
                keyProvider = keyProvider,
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects activeKeyId containing whitespace`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("space-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "my key",
                keyProvider = keyProvider,
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open accepts valid activeKeyId with allowed special characters`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("valid-special-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "k8s_prod:us-east-1@v2",
                keyProvider = keyProvider,
            ),
        )
        val stores = FileBackedSovereignStores.open(config)
        stores.close()
    }

    // ── Encryption key validation ──────────────────────────────────────

    @Test
    fun `open rejects non-AES encryption key`() {
        val desKey = KeyGenerator.getInstance("DES").generateKey()
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("non-aes-key"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "test-key",
                keyProvider = FileStoreEncryptionKeyProvider { desKey },
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects AES key that is not 256 bit`() {
        val aes128Key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("aes-128-key"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "test-key",
                keyProvider = FileStoreEncryptionKeyProvider { aes128Key },
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    // ── Symlink validation ─────────────────────────────────────────────

    @Test
    fun `open rejects symlink on tramai lock`() {
        val setupRoot = rootDir.resolve("lock-symlink")
        val realTarget = Files.createTempDirectory("lock-target-")

        // First open to create proper structure
        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        // Replace .tramai.lock with a symlink
        val lockPath = setupRoot.resolve(".tramai.lock")
        Files.delete(lockPath)
        Files.createSymbolicLink(lockPath, realTarget)

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects symlink on manifest json`() {
        val setupRoot = rootDir.resolve("manifest-symlink")
        val realTarget = rootDir.resolve("manifest-target")

        // First open to create proper structure
        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        // Replace manifest.json with a symlink
        val manifestPath = setupRoot.resolve("manifest.json")
        Files.delete(manifestPath)
        Files.createSymbolicLink(manifestPath, realTarget)

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects symlink on approvals subdirectory`() {
        val setupRoot = rootDir.resolve("approvals-symlink")
        val realApprovals = rootDir.resolve("real-approvals")
        Files.createDirectories(realApprovals)

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val approvalsDir = setupRoot.resolve("approvals")
        approvalsDir.toFile().deleteRecursively()
        Files.createSymbolicLink(approvalsDir, realApprovals)

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects symlink on continuations subdirectory`() {
        val setupRoot = rootDir.resolve("continuations-symlink")
        val realCont = rootDir.resolve("real-continuations")
        Files.createDirectories(realCont)

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val contDir = setupRoot.resolve("continuations")
        contDir.toFile().deleteRecursively()
        Files.createSymbolicLink(contDir, realCont)

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects symlink on audit subdirectory`() {
        val setupRoot = rootDir.resolve("audit-symlink")
        val realAudit = rootDir.resolve("real-audit")
        Files.createDirectories(realAudit)

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val auditDir = setupRoot.resolve("audit")
        auditDir.toFile().deleteRecursively()
        Files.createSymbolicLink(auditDir, realAudit)

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    // ── Permissions validation ─────────────────────────────────────────

    @Test
    fun `open rejects wrong permissions on manifest json`() {
        val setupRoot = rootDir.resolve("manifest-perms")

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val manifestPath = setupRoot.resolve("manifest.json")
        Files.setPosixFilePermissions(manifestPath, PosixFilePermissions.fromString("rwx------"))

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects wrong permissions on approvals subdirectory`() {
        val setupRoot = rootDir.resolve("approvals-perms")

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val approvalsDir = setupRoot.resolve("approvals")
        Files.setPosixFilePermissions(approvalsDir, PosixFilePermissions.fromString("rwxrwx---"))

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects wrong permissions on continuations subdirectory`() {
        val setupRoot = rootDir.resolve("continuations-perms")

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val contDir = setupRoot.resolve("continuations")
        Files.setPosixFilePermissions(contDir, PosixFilePermissions.fromString("rwxrwx---"))

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects wrong permissions on audit subdirectory`() {
        val setupRoot = rootDir.resolve("audit-perms")

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val auditDir = setupRoot.resolve("audit")
        Files.setPosixFilePermissions(auditDir, PosixFilePermissions.fromString("rwxrwx---"))

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }
}
