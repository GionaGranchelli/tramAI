@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.persistence.file.FileStoreCorruptionException
import dev.tramai.persistence.file.FileStoreUnsupportedFormatException
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxGovernance
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 0.7.1d governed file outbox continuity.
 *
 * Every persistence transition must carry the complete canonical [GovernedRunIdentity] unchanged, and
 * a governed V2 record must never be rewritten as V1. These discriminators observe the identity after
 * each step, including across store close/reopen, so an accidental `copy(...)` downgrade is caught
 * rather than merely exercised.
 */
class FileGovernedSovereignOpsAuditOutboxStoreTest {
    private val rootDir: Path = Files.createTempDirectory("tramai-governed-outbox-").toAbsolutePath()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun caseDir(name: String): Path = Files.createDirectories(rootDir.resolve(name))

    private fun fileStore(dir: Path): FileSovereignOpsAuditOutboxStore = FileSovereignOpsAuditOutboxStore(dir, key)

    private fun governed(fileStore: FileSovereignOpsAuditOutboxStore): GovernedFileSovereignOpsAuditOutboxStore =
        GovernedFileSovereignOpsAuditOutboxStore(fileStore)

    // ---------- fixtures ----------

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun identity(
        workloadId: String = "wl-1",
        runId: String = "run-1",
    ): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId(workloadId),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("cfg-1"),
                            version = ConfigurationVersion("v1"),
                        ),
                    environmentId = EnvironmentId("env-1"),
                    deploymentId = DeploymentId("dep-1"),
                ),
            runId = RunId(runId),
        )

    private fun record(
        identity: GovernedRunIdentity,
        outboxId: String = UUID.randomUUID().toString(),
        eventKey: String = "event-${UUID.randomUUID()}",
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateIdDigest = sha256("aggregate"),
            eventKey = eventKey,
            actor = "tester",
            workflowRunId = identity.runId.value,
            correlationId = null,
            approvalStatus = "DENIED",
            approvalVersion = 1L,
            reasonDigest = sha256("reason"),
            reasonLength = 6,
        )

    // ---------- lifecycle ----------

    @Test
    fun `governed identity survives every transition and two restarts`() {
        runBlocking<Unit> {
            val dir = caseDir("lifecycle")
            val expected = identity()
            val original = record(expected)

            var concrete = fileStore(dir)
            var store = governed(concrete)
            store.appendGoverned(GovernedSovereignOpsAuditOutboxRecord(original, expected))
            assertIdentity(expected, store, original.outboxId)
            assertRecordFileCount(dir, 1)

            store.markReadyForDispatch(original.outboxId, SovereignOpsAuditOutboxStatus.PREPARED)
            assertIdentity(expected, store, original.outboxId)

            concrete.close()
            concrete = fileStore(dir)
            store = governed(concrete)
            assertIdentity(expected, store, original.outboxId)

            assertEquals(1, store.claimPending("worker-1", 1, Instant.parse("2026-09-14T10:00:00Z")).size)
            assertIdentity(expected, store, original.outboxId)

            store.markFailed(
                outboxId = original.outboxId,
                expectedStatus = SovereignOpsAuditOutboxStatus.EMITTING,
                expectedAttemptCount = 1,
                errorCode = "transient",
                retryable = true,
            )
            assertIdentity(expected, store, original.outboxId)

            concrete.close()
            concrete = fileStore(dir)
            store = governed(concrete)
            assertIdentity(expected, store, original.outboxId)

            assertEquals(1, store.claimPending("worker-2", 1, Instant.parse("2026-09-14T11:00:00Z")).size)
            assertIdentity(expected, store, original.outboxId)

            store.markEmitted(
                outboxId = original.outboxId,
                expectedStatus = SovereignOpsAuditOutboxStatus.EMITTING,
                expectedAttemptCount = 2,
                emittedAt = Instant.parse("2026-09-14T11:05:00Z"),
            )
            assertIdentity(expected, store, original.outboxId)

            concrete.verifyAll()
            assertRecordFileCount(dir, 1)
        }
    }

    @Test
    fun `legacy record reads as Legacy and an absent record as NoRecord`() {
        runBlocking<Unit> {
            val store = governed(fileStore(caseDir("legacy")))
            val identity = identity()
            val legacy = record(identity)
            store.append(legacy)

            val legacyRecord =
                when (val resolved = store.governanceById(legacy.outboxId)) {
                    is SovereignOpsAuditOutboxGovernance.Legacy -> resolved.record
                    else -> throw AssertionError("expected Legacy, got \$resolved")
                }
            assertEquals(legacy, legacyRecord)

            assertEquals(
                SovereignOpsAuditOutboxGovernance.NoRecord,
                store.governanceById(UUID.randomUUID().toString()),
            )
        }
    }

    @Test
    fun `governed append keeps duplicate protection and writes no second file`() {
        runBlocking<Unit> {
            val dir = caseDir("duplicates")
            val store = governed(fileStore(dir))
            val identity = identity()
            val original = record(identity)
            store.appendGoverned(GovernedSovereignOpsAuditOutboxRecord(original, identity))
            assertRecordFileCount(dir, 1)

            val duplicateId =
                assertFailsWith<IllegalArgumentException> {
                    store.appendGoverned(
                        GovernedSovereignOpsAuditOutboxRecord(original.copy(eventKey = "other-event"), identity),
                    )
                }
            assertEquals("tramai-sovereign-ops-outbox-duplicate-id", duplicateId.message)

            val duplicateEventKey =
                assertFailsWith<IllegalArgumentException> {
                    store.appendGoverned(
                        GovernedSovereignOpsAuditOutboxRecord(record(identity, eventKey = original.eventKey), identity),
                    )
                }
            assertTrue(duplicateEventKey.message?.contains("duplicate") == true, "got ${duplicateEventKey.message}")
            assertRecordFileCount(dir, 1)
        }
    }

    @Test
    fun `verifyAll accepts valid V1 and V2`() {
        runBlocking<Unit> {
            val dir = caseDir("mixed")
            val concrete = fileStore(dir)
            val store = governed(concrete)
            val identity = identity()
            store.append(record(identity))
            store.appendGoverned(GovernedSovereignOpsAuditOutboxRecord(record(identity), identity))
            concrete.verifyAll()
            assertRecordFileCount(dir, 2)
        }
    }

    // ---------- codec fail-closed matrix ----------

    @Test
    fun `unknown schema version is unsupported format, never legacy`() {
        assertFailsWith<FileStoreUnsupportedFormatException> {
            decodeOutboxRecord("""{"schemaVersion":3,"record":{},"governedRunIdentity":{}}""")
        }
    }

    @Test
    fun `malformed payload is corruption`() {
        assertFailsWith<FileStoreCorruptionException> { decodeOutboxRecord("{ not json") }
    }

    @Test
    fun `V2 whose identity disagrees with the run id fails closed`() {
        runBlocking<Unit> {
            val identity = identity()
            val json = governedJson(record(identity), identity)
            val tampered = json.replace("\"runId\":\"run-1\"", "\"runId\":\"run-other\"")
            assertTrue(tampered != json, "tamper must change the payload")
            assertFailsWith<FileStoreCorruptionException> { decodeOutboxRecord(tampered) }
        }
    }

    @Test
    fun `partial V2 identity fails closed`() {
        runBlocking<Unit> {
            val identity = identity()
            val json = governedJson(record(identity), identity)
            val tampered = json.replace(",\"deploymentId\":\"dep-1\"", "")
            assertTrue(tampered != json, "tamper must change the payload")
            assertFailsWith<FileStoreCorruptionException> { decodeOutboxRecord(tampered) }
        }
    }

    @Test
    fun `V1 payload decodes with no governed identity and re-encodes as V1`() {
        runBlocking<Unit> {
            val v1Json = record(identity()).toPersistedV1(version = 0L).toJson()
            val decoded = decodeOutboxRecord(v1Json)
            assertEquals(null, decoded.runIdentity)

            val reEncoded = encodeOutboxRecord(decoded, 1L)
            assertTrue(reEncoded.contains("\"schemaVersion\":1"), "a legacy record must never be re-encoded as V2")
            assertEquals(decoded.record, decodeOutboxRecord(reEncoded).record)
        }
    }

    @Test
    fun `a governed payload re-encodes as V2 with the identity intact`() {
        runBlocking<Unit> {
            val identity = identity()
            val decoded = decodeOutboxRecord(governedJson(record(identity), identity))
            assertEquals(identity, requireNotNull(decoded.runIdentity))

            val reEncoded = encodeOutboxRecord(decoded, 1L)
            assertTrue(reEncoded.contains("\"schemaVersion\":2"), "a governed record must stay V2 across rewrites")

            val roundTripped = decodeOutboxRecord(reEncoded)
            assertEquals(identity, roundTripped.runIdentity)
            assertEquals(decoded.record, roundTripped.record)
        }
    }

    // ---------- helpers ----------

    private fun assertRecordFileCount(
        dir: Path,
        expected: Int,
    ) {
        val files =
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.count()
            }
        assertEquals(expected.toLong(), files, "expected $expected durable file(s) under $dir")
    }

    private suspend fun assertIdentity(
        expected: GovernedRunIdentity,
        store: GovernedFileSovereignOpsAuditOutboxStore,
        outboxId: String,
    ) {
        val persisted =
            when (val resolved = store.governanceById(outboxId)) {
                is SovereignOpsAuditOutboxGovernance.Governed -> resolved.record.runIdentity
                else -> throw AssertionError("expected governed, got \$resolved")
            }
        assertEquals(expected, persisted, "the complete canonical identity must survive unchanged")
    }

    private fun governedJson(
        base: SovereignOpsAuditOutboxRecord,
        identity: GovernedRunIdentity,
    ): String =
        encodeOutboxRecord(
            DecodedOutboxRecord(base, identity, outboxRecordVersion = 0L),
            outboxRecordVersion = 0L,
        )
}
