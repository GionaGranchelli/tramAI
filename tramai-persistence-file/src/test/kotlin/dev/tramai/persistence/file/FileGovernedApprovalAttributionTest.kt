package dev.tramai.persistence.file

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.engine.approval.ApprovalAttributionKeys
import dev.tramai.engine.approval.ApprovalRunAttribution
import dev.tramai.engine.approval.GovernedApprovalStore
import dev.tramai.testing.persistence.approval.ApprovalStoreFixtures
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.path.exists
import kotlin.test.assertFailsWith

/**
 * Governed approval attribution persistence on the file-backed store (0.7.1d1).
 *
 * The invariant under test: a governed file-backed approval cannot lose, partially preserve, or
 * silently downgrade its governed attribution as its persisted lifecycle advances. Every assertion
 * after a lifecycle step re-reads from disk (a fresh store over the same directory), because an
 * in-memory assertion cannot prove what was persisted.
 */
class FileGovernedApprovalAttributionTest {
    private val rootDir: Path = Files.createTempDirectory("tramai-governed-approval-").toAbsolutePath()
    private val now: Instant = Instant.parse("2025-06-01T12:00:00Z")
    private val expiry: Instant = now.plusSeconds(600)
    private val clock: Clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val testKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @BeforeEach
    fun setup() {
        Files.createDirectories(rootDir.resolve("approvals"))
        Files.setPosixFilePermissions(
            rootDir.resolve("approvals"),
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwx------"),
        )
    }

    @AfterEach
    fun cleanup() {
        if (rootDir.exists()) rootDir.toFile().deleteRecursively()
    }

    // ── A. Ungoverned V1 ─────────────────────────────────────────────────

    @Test
    fun `an ungoverned approval persists as V1 and synthesizes no attribution`() =
        runBlocking<Unit> {
            val store = store()
            val request = ApprovalStoreFixtures.pending("legacy-1", now, expiry)

            store.create(request)
            assertThat(persistedSchemaVersion("legacy-1")).isEqualTo(1)
            assertThat(store.attributionOf("legacy-1")).isEqualTo(ApprovalRunAttribution.Ungoverned)

            // Transitions keep it a valid V1 record, and reopen still reads it.
            store.transition("legacy-1", 0L, ApprovalTransition.Approve(decidedBy = "approver-1"))
            assertThat(persistedSchemaVersion("legacy-1")).isEqualTo(1)

            val reopened = store()
            assertThat(reopened.get("legacy-1")?.status).isEqualTo(ApprovalStatus.APPROVED)
            assertThat(reopened.attributionOf("legacy-1")).isEqualTo(ApprovalRunAttribution.Ungoverned)
        }

    @Test
    fun `a denied ungoverned approval stays V1 with no attribution`() =
        runBlocking<Unit> {
            val store = store()
            store.create(ApprovalStoreFixtures.pending("legacy-deny", now, expiry))

            store.transition("legacy-deny", 0L, ApprovalTransition.Deny(decidedBy = "approver-1", comment = "no"))

            assertThat(persistedSchemaVersion("legacy-deny")).isEqualTo(1)
            assertThat(store().attributionOf("legacy-deny")).isEqualTo(ApprovalRunAttribution.Ungoverned)
        }

    // ── B. Governed V2 initial persistence ───────────────────────────────

    @Test
    fun `a governed approval persists as V2 and survives reopen`() =
        runBlocking<Unit> {
            val store = store()
            val request = ApprovalStoreFixtures.pending("gov-create", now, expiry)
            val attribution = governedAttribution(request)

            store.createGovernedApproval(request, attribution)

            assertThat(persistedSchemaVersion("gov-create")).isEqualTo(2)
            assertThat(store.attributionOf("gov-create")).isEqualTo(attribution)

            val reopened = store()
            assertThat(reopened.attributionOf("gov-create")).isEqualTo(attribution)
            // The canonical run id is still sourced from the binding, not from the attribution DTO.
            assertThat((reopened.attributionOf("gov-create") as ApprovalRunAttribution.Governed).identity.runId)
                .isEqualTo(RunId(request.binding.workflowRunId))
        }

    // ── C/G. Lifecycle preservation (the rewrite hazard) ─────────────────

    @Test
    fun `approving a governed approval keeps it V2 with identical attribution after reopen`() =
        runBlocking<Unit> {
            val store = store()
            val request = ApprovalStoreFixtures.pending("gov-approve", now, expiry)
            store.createGovernedApproval(request, governedAttribution(request))

            val decided = store.transition("gov-approve", 0L, ApprovalTransition.Approve(decidedBy = "approver-1"))

            // This is the regression this test exists for: the transition rewrites the whole record.
            // If attribution propagation is ever removed from that rewrite path, this fails.
            assertThat(persistedSchemaVersion("gov-approve")).isEqualTo(2)
            val reopened = store()
            assertThat(reopened.attributionOf("gov-approve")).isEqualTo(governedAttribution(request))
            assertThat(reopened.get("gov-approve")?.status).isEqualTo(ApprovalStatus.APPROVED)
            assertThat(decided.status).isEqualTo(ApprovalStatus.APPROVED)
        }

    @Test
    fun `denying a governed approval keeps it V2 with identical attribution after reopen`() =
        runBlocking<Unit> {
            val store = store()
            val request = ApprovalStoreFixtures.pending("gov-deny", now, expiry)
            store.createGovernedApproval(request, governedAttribution(request))

            store.transition("gov-deny", 0L, ApprovalTransition.Deny(decidedBy = "approver-1", comment = "rejected"))

            assertThat(persistedSchemaVersion("gov-deny")).isEqualTo(2)
            assertThat(store().attributionOf("gov-deny")).isEqualTo(governedAttribution(request))
        }

    @Test
    fun `consuming a governed approval keeps it V2 with identical attribution after reopen`() =
        runBlocking<Unit> {
            val store = store()
            val request = ApprovalStoreFixtures.pending("gov-consume", now, expiry)
            store.createGovernedApproval(request, governedAttribution(request))
            val decided = store.transition("gov-consume", 0L, ApprovalTransition.Approve(decidedBy = "approver-1"))

            val receipt =
                store.consumeApprovedOrReplay(
                    "gov-consume",
                    decided.version,
                    request.binding.approvalTokenDigest,
                    consumedBy = "consumer-1",
                )

            assertThat(receipt.replayed).isFalse()
            assertThat(persistedSchemaVersion("gov-consume")).isEqualTo(2)
            val reopened = store()
            assertThat(reopened.attributionOf("gov-consume")).isEqualTo(governedAttribution(request))
            assertThat(reopened.get("gov-consume")?.consumedBy).isEqualTo("consumer-1")
        }

    @Test
    fun `the replay path reads a governed approval without altering its attribution`() =
        runBlocking<Unit> {
            val store = store()
            val request = ApprovalStoreFixtures.pending("gov-replay", now, expiry)
            store.createGovernedApproval(request, governedAttribution(request))
            val decided = store.transition("gov-replay", 0L, ApprovalTransition.Approve(decidedBy = "approver-1"))
            store.consumeApprovedOrReplay(
                "gov-replay",
                decided.version,
                request.binding.approvalTokenDigest,
                "consumer-1",
            )

            val replay =
                store.consumeApprovedOrReplay(
                    "gov-replay",
                    decided.version,
                    request.binding.approvalTokenDigest,
                    "consumer-1",
                )

            assertThat(replay.replayed).isTrue()
            assertThat(persistedSchemaVersion("gov-replay")).isEqualTo(2)
            assertThat(store().attributionOf("gov-replay")).isEqualTo(governedAttribution(request))
        }

    // ── D. Terminal behaviour unchanged ──────────────────────────────────

    @Test
    fun `a terminal governed approval still rejects further transitions and keeps its attribution`() =
        runBlocking<Unit> {
            val store = store()
            val request = ApprovalStoreFixtures.pending("gov-terminal", now, expiry)
            store.createGovernedApproval(request, governedAttribution(request))
            store.transition("gov-terminal", 0L, ApprovalTransition.Approve(decidedBy = "approver-1"))

            assertFailsWith<dev.tramai.core.exception.IllegalApprovalTransitionException> {
                store.transition("gov-terminal", 1L, ApprovalTransition.Deny(decidedBy = "approver-2"))
            }

            assertThat(persistedSchemaVersion("gov-terminal")).isEqualTo(2)
            assertThat(store().attributionOf("gov-terminal")).isEqualTo(governedAttribution(request))
        }

    // ── E. Strict V2 corruption ──────────────────────────────────────────

    @Test
    fun `every missing attribution component fails decoding instead of becoming ungoverned`() =
        runBlocking<Unit> {
            for (component in PERSISTED_COMPONENTS) {
                val id = "gov-corrupt-${component.replace("Id", "").lowercase()}"
                val store = store()
                val request = ApprovalStoreFixtures.pending(id, now, expiry)
                store.createGovernedApproval(request, governedAttribution(request))
                tamper(id) { (it.get("attribution") as ObjectNode).remove(component) }

                val reopened = store()
                assertFailsWith<FileStoreCorruptionException>("missing $component must fail closed") {
                    reopened.attributionOf(id)
                }
                // Never silently reinterpreted as an un-attributed legacy record.
                assertFailsWith<FileStoreCorruptionException> { reopened.get(id) }
            }
        }

    @Test
    fun `a V2 record without its attribution block fails decoding`() =
        runBlocking<Unit> {
            val store = store()
            val request = ApprovalStoreFixtures.pending("gov-no-block", now, expiry)
            store.createGovernedApproval(request, governedAttribution(request))
            tamper("gov-no-block") { it.remove("attribution") }

            assertFailsWith<FileStoreCorruptionException> { store().attributionOf("gov-no-block") }
        }

    @Test
    fun `a blank attribution component fails decoding rather than decoding as a partial identity`() =
        runBlocking<Unit> {
            val store = store()
            val request = ApprovalStoreFixtures.pending("gov-blank", now, expiry)
            store.createGovernedApproval(request, governedAttribution(request))
            tamper("gov-blank") { it.get("attribution").let { a -> a as ObjectNode }.put("deploymentId", "  ") }

            assertFailsWith<FileStoreCorruptionException> { store().attributionOf("gov-blank") }
        }

    @Test
    fun `an unsupported approval schema version fails instead of decoding as V1`() =
        runBlocking<Unit> {
            val store = store()
            store.create(ApprovalStoreFixtures.pending("gov-version", now, expiry))
            tamper("gov-version") { it.put("schemaVersion", 3) }

            val failure =
                assertFailsWith<IllegalArgumentException> { store().attributionOf("gov-version") }
            assertThat(failure.message.orEmpty()).contains("unsupported-approval-schema-version")
        }

    @Test
    fun `the run id is sourced from the binding and never from a duplicated persisted copy`() =
        runBlocking<Unit> {
            val store = store()
            val request = ApprovalStoreFixtures.pending("gov-binding", now, expiry)
            store.createGovernedApproval(request, governedAttribution(request))

            // The attribution block persists no run id at all, so a second identifier cannot exist;
            // the decoded identity takes its run id from the canonical binding.
            val attributionNode = persistedJson("gov-binding").get("attribution") as ObjectNode
            assertThat(attributionNode.has("runId")).isFalse()
            assertThat(attributionNode.size()).isEqualTo(PERSISTED_COMPONENTS.size + 1)

            val identity =
                (store().attributionOf("gov-binding") as ApprovalRunAttribution.Governed).identity
            assertThat(identity.runId).isEqualTo(RunId(request.binding.workflowRunId))
        }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * A fresh governed store over the same directory: proves what is on disk, not what is in memory.
     *
     * The governed capability is a wrapper over the approval store, so this exercises the contract a
     * caller actually obtains rather than an implementation detail.
     */
    private fun store(): GovernedApprovalStore =
        GovernedFileApprovalStore(FileApprovalStore(rootDir, testKey, config(), FileStoreLease(), clock))

    private fun config() =
        FileBackedStoreConfiguration(
            rootDirectory = rootDir,
            encryption =
                FileStoreEncryptionConfiguration(
                    activeKeyId = "test-key",
                    keyProvider = FileStoreEncryptionKeyProvider { testKey },
                ),
            verifyOnOpen = false,
        )

    private fun governedAttribution(request: ApprovalRequest): ApprovalRunAttribution.Governed =
        ApprovalRunAttribution.Governed(
            GovernedRunIdentity(
                deployment =
                    WorkloadDeploymentIdentity(
                        workloadId = WorkloadId("claims-triage"),
                        configuration =
                            WorkloadConfigurationIdentity(
                                id = ConfigurationId("claims-triage-prod"),
                                version = ConfigurationVersion("v7"),
                            ),
                        environmentId = EnvironmentId("prod-eu"),
                        deploymentId = DeploymentId("deploy-42"),
                    ),
                runId = RunId(request.binding.workflowRunId),
            ),
        )

    private fun recordPath(approvalId: String): Path =
        rootDir
            .resolve("approvals")
            .resolve("${FileStoreSha256.digest(RECORD_TYPE, approvalId)}$FILE_EXTENSION")

    private fun persistedJson(approvalId: String) =
        FILE_STORE_JSON.readTree(
            String(
                FileStoreUtil.readAndDecrypt(
                    recordPath(approvalId),
                    RECORD_TYPE,
                    FileStoreSha256.digest(RECORD_TYPE, approvalId),
                    testKey,
                    "test-key",
                ),
                Charsets.UTF_8,
            ),
        )

    private fun persistedSchemaVersion(approvalId: String): Int = persistedJson(approvalId).get("schemaVersion").asInt()

    private fun tamper(
        approvalId: String,
        transform: (ObjectNode) -> Unit,
    ) = rewrite(approvalId, transform)

    private fun rewrite(
        approvalId: String,
        transform: (ObjectNode) -> Unit,
    ) {
        val node = persistedJson(approvalId) as ObjectNode
        transform(node)
        val json = FILE_STORE_JSON.writeValueAsString(node).toByteArray(Charsets.UTF_8)
        FileStoreUtil.atomicEncryptWrite(
            recordPath(approvalId),
            RECORD_TYPE,
            FileStoreSha256.digest(RECORD_TYPE, approvalId),
            "test-key",
            testKey,
            json,
        )
    }

    private companion object {
        const val RECORD_TYPE = "approval-request"
        const val FILE_EXTENSION = ".tram.enc"
        val PERSISTED_COMPONENTS =
            listOf("workloadId", "configurationId", "configurationVersion", "environmentId", "deploymentId")
    }
}
