package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.spring.sovereign.ops.SovereignOpsAuditEmitter
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatcher
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxQuarantineStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 0.7.6f slice 3 -- durable evidence quarantine on the encrypted file store.
 *
 * The file backend has the harder recovery problem: identifiers live *inside* the ciphertext, so an
 * undecodable record has no recoverable `outboxId` or `eventKey` at all. Recovery is therefore keyed by
 * the record-key digest from the file name, and the marker is the only durable handle. The assertions
 * below are about exactly that: the record stays byte-identical, the marker survives a cold restart with
 * no index populated, and a wrong key quarantines nothing.
 */
class FileSovereignOpsAuditOutboxQuarantineTest {
    @TempDir
    lateinit var root: Path

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `a mixed batch quarantines the damaged file, leaves neighbours byte-identical and restarts cleanly`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("file-healthy-1"))
            store.appendDispatchable(refusal("file-healthy-2"))
            val damagedDigest = writeUndecodableFile("a".repeat(64))
            val before = snapshot()

            val report = store.quarantineStore()!!.quarantineUndecodableEvidence(limit = 10, now = now)

            assertThat(report.scanned).isEqualTo(3)
            assertThat(report.decodable).isEqualTo(2)
            assertThat(report.quarantined.map { it.locator }).containsExactly(damagedDigest)
            assertThat(report.quarantined.single().label)
                .isEqualTo(SovereignOpsAuditOutboxQuarantineStore.LABEL_UNDECODABLE)

            // Every stored record is untouched, including the damaged one.
            assertThat(snapshot()).isEqualTo(before)
            assertThat(markerPath(damagedDigest)).exists()

            // Healthy records still dispatch.
            val claimed = store.claimPending("dispatcher", 10, now.plusSeconds(1))
            assertThat(claimed.map { it.outboxId }).containsExactlyInAnyOrder("file-healthy-1", "file-healthy-2")

            // A second run adds nothing and rewrites no marker.
            val markerBefore = Files.readAllBytes(markerPath(damagedDigest)).toList()
            val second = store.quarantineStore()!!.quarantineUndecodableEvidence(limit = 10, now = now.plusSeconds(2))
            assertThat(second.quarantined).isEmpty()
            assertThat(second.skipped).isEqualTo(1)
            assertThat(Files.readAllBytes(markerPath(damagedDigest)).toList()).isEqualTo(markerBefore)
        }

    @Test
    fun `a cold restart honours the marker without decoding the damaged record`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("restart-healthy"))
            val damagedDigest = writeUndecodableFile("b".repeat(64))
            store.quarantineStore()!!.quarantineUndecodableEvidence(limit = 10, now = now)

            // A brand-new store over the same directory: the constructor rebuilds the index from disk.
            val restarted = FileSovereignOpsAuditOutboxStore(root = root, key = key)

            assertThat(restarted.get("restart-healthy")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            assertThat(restarted.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10).map { it.outboxId })
                .containsExactly("restart-healthy")
            assertThat(restarted.quarantineStore()!!.listQuarantined(10).map { it.locator })
                .containsExactly(damagedDigest)
            assertThat(restarted.claimPending("dispatcher", 10, now.plusSeconds(3)).map { it.outboxId })
                .containsExactly("restart-healthy")
        }

    @Test
    fun `an unmarked undecodable record does not block startup or healthy listings`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("unmarked-healthy"))
            writeUndecodableFile("9".repeat(64))

            // No quarantine has run yet: initialization must still succeed, and the healthy record must be
            // listable and claimable while the damaged one is simply not a candidate.
            val restarted = FileSovereignOpsAuditOutboxStore(root = root, key = key)

            assertThat(restarted.get("unmarked-healthy")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            assertThat(restarted.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10).map { it.outboxId })
                .containsExactly("unmarked-healthy")
            assertThat(restarted.claimPending("dispatcher", 10, now.plusSeconds(1)).map { it.outboxId })
                .containsExactly("unmarked-healthy")
            assertThat(restarted.quarantineStore()!!.listQuarantined(10)).isEmpty()
        }

    // --- discriminator 21: identifiers are unrecoverable and never invented ----------------------

    @Test
    fun `an undecodable record reports null identifiers and returns its original bytes`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("identifier-control"))
            val damagedDigest = writeUndecodableFile("c".repeat(64))
            val originalBytes = Files.readAllBytes(outboxDir().resolve("$damagedDigest.tram.enc")).toList()
            store.quarantineStore()!!.quarantineUndecodableEvidence(limit = 10, now = now)

            // No index is pre-populated in this instance's view of the damaged record: recovery is by digest.
            val restarted = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            val summary = restarted.quarantineStore()!!.listQuarantined(10).single { it.locator == damagedDigest }

            assertThat(summary.locator).isEqualTo(damagedDigest)
            assertThat(summary.outboxId).isNull()
            assertThat(summary.eventKey).isNull()
            assertThat(summary.payloadDigest).startsWith("sha256:")
            assertThat(summary.quarantinedAt).isEqualTo(now)
            assertThat(summary.failureDetail).isNotNull()
            assertThat(summary.failureDetail!!.length)
                .isLessThanOrEqualTo(SovereignOpsAuditOutboxQuarantineStore.MAX_FAILURE_DETAIL)
            val storedBytes = restarted.quarantineStore()!!.readQuarantinedCiphertext(damagedDigest)
            assertThat(storedBytes?.toList()).isEqualTo(originalBytes)
            // A locator that is not quarantined, or not a digest at all, resolves to nothing.
            assertThat(restarted.quarantineStore()!!.readQuarantinedCiphertext("d".repeat(64))).isNull()
            assertThat(restarted.quarantineStore()!!.readQuarantinedCiphertext("../../etc/passwd")).isNull()
        }

    // --- discriminator 22: a wrong key quarantines nothing ---------------------------------------

    @Test
    fun `a wrong deployment key aborts instead of quarantining healthy evidence`() =
        runBlocking<Unit> {
            val writing = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            writing.appendDispatchable(refusal("wrong-key-1"))
            writing.appendDispatchable(refusal("wrong-key-2"))
            val before = snapshot()

            val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val reading = FileSovereignOpsAuditOutboxStore(root = root, key = otherKey)

            assertThatThrownBy {
                runBlocking { reading.quarantineStore()!!.quarantineUndecodableEvidence(limit = 10, now = now) }
            }.hasMessage(SovereignOpsAuditOutboxQuarantineStore.ERROR_DECRYPTION_UNAVAILABLE)

            assertThat(reading.quarantineStore()!!.listQuarantined(10)).isEmpty()
            assertThat(Files.exists(root.resolve("quarantine"))).isTrue()
            assertThat(snapshot()).isEqualTo(before)
        }

    // --- discriminator 24: decodable and PREPARED records are never touched ---------------------

    @Test
    fun `quarantine touches neither decodable nor PREPARED records`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("untouched-healthy"))
            store.append(prepared("untouched-prepared"))
            val damagedDigest = writeUndecodableFile("e".repeat(64))

            val report = store.quarantineStore()!!.quarantineUndecodableEvidence(limit = 10, now = now)

            assertThat(report.scanned).isEqualTo(3)
            assertThat(report.quarantined.map { it.locator }).containsExactly(damagedDigest)
            assertThat(store.get("untouched-healthy")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            assertThat(store.get("untouched-prepared")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PREPARED)
            assertThat(store.quarantineStore()!!.listQuarantined(10).map { it.locator }).containsExactly(damagedDigest)
        }

    // --- dispatch-boundary integration ---------------------------------------------------------

    @Test
    fun `the dispatcher quarantines a damaged record and still emits the healthy one`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("dispatch-healthy"))
            writeUndecodableFile("f".repeat(64))
            val emittedIds = mutableListOf<String>()
            val emitter =
                object : SovereignOpsAuditEmitter {
                    override suspend fun approvalDenied(
                        approvalId: String,
                        actor: String,
                        reason: String,
                        approvalStatus: String,
                        approvalVersion: Long?,
                        workflowRunId: String?,
                        correlationId: String?,
                    ) = Unit

                    override suspend fun approvalDeniedFromOutbox(record: SovereignOpsAuditOutboxRecord) {
                        emittedIds.add(record.outboxId)
                    }
                }
            val dispatcher =
                SovereignOpsAuditOutboxDispatcher(
                    outboxStore = store,
                    auditEmitter = emitter,
                    clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC),
                )

            val result = dispatcher.dispatchPending(limit = 10)

            assertThat(result.claimed).isEqualTo(1)
            assertThat(result.emitted).isEqualTo(1)
            assertThat(emittedIds).containsExactly("dispatch-healthy")
            assertThat(store.quarantineStore()!!.listQuarantined(10)).hasSize(1)
            assertThat(store.get("dispatch-healthy")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
        }

    // --- helpers --------------------------------------------------------------------------------

    /** Writes a committed-looking record file whose envelope cannot be decrypted or decoded. */
    private fun writeUndecodableFile(digest: String): String {
        val target = outboxDir().resolve("$digest.tram.enc")
        Files.writeString(
            target,
            """{"envelopeVersion":1,"recordType":"ops-audit-outbox","recordKeyDigest":"$digest",""" +
                """"keyId":"default","nonceBase64":"AAAAAAAAAAAAAAAA","ciphertextBase64":"bm90LWEtY2lwaGVydGV4dA=="}""",
            StandardOpenOption.CREATE_NEW,
        )
        // Match the store's own file permissions: this record must fail on its payload, never on access.
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
        return digest
    }

    private fun outboxDir(): Path = root.resolve("ops-audit-outbox")

    private fun markerPath(locator: String): Path = root.resolve("quarantine").resolve("$locator.quarantine.json")

    private fun snapshot(): Map<String, List<Byte>> {
        val dir = outboxDir()
        if (!Files.exists(dir)) return emptyMap()
        val entries = mutableMapOf<String, List<Byte>>()
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tram.enc") }.forEach { path ->
                entries[path.fileName.toString()] = Files.readAllBytes(path).toList()
            }
        }
        return entries
    }

    private fun refusal(outboxId: String): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateType = "approval",
            aggregateIdDigest = "sha256:" + "a".repeat(64),
            operation = "authorizationRefusal",
            eventKey = "event-$outboxId",
            actor = "operator-1",
            workflowRunId = null,
            correlationId = null,
            approvalStatus = null,
            approvalVersion = null,
            reasonDigest = "sha256:" + "b".repeat(64),
            reasonLength = 11,
            createdAt = now,
            status = SovereignOpsAuditOutboxStatus.PENDING,
            evidenceKind = SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL,
            actorIssuer = "https://issuer.example",
            actorIdentityDigest = "0123456789abcdef".repeat(4),
        )

    private fun prepared(outboxId: String): SovereignOpsAuditOutboxRecord =
        refusal(outboxId).copy(status = SovereignOpsAuditOutboxStatus.PREPARED, evidenceKind = null)
}
