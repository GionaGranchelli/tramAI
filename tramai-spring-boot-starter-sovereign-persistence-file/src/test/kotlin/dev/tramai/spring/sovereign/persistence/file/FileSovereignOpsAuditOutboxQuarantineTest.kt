package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.persistence.file.AesGcmFileEncryption
import dev.tramai.persistence.file.EncryptedFileEnvelopeV1
import dev.tramai.persistence.file.FileStoreSha256
import dev.tramai.spring.sovereign.ops.SovereignOpsAuditEmitter
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatcher
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxQuarantineStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
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

            val report = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

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
            val second = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now.plusSeconds(2))
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
            store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            // A brand-new store over the same directory: the constructor rebuilds the index from disk.
            val restarted = FileSovereignOpsAuditOutboxStore(root = root, key = key)

            assertThat(restarted.get("restart-healthy")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            assertThat(restarted.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10).map { it.outboxId })
                .containsExactly("restart-healthy")
            assertThat(restarted.quarantineStore().listQuarantined(10).map { it.locator })
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
            assertThat(restarted.quarantineStore().listQuarantined(10)).isEmpty()
        }

    // --- discriminator 21: identifiers are unrecoverable and never invented ----------------------

    @Test
    fun `an undecodable record reports null identifiers and returns its original bytes`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("identifier-control"))
            val damagedDigest = writeUndecodableFile("c".repeat(64))
            val originalBytes = Files.readAllBytes(outboxDir().resolve("$damagedDigest.tram.enc")).toList()
            store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            // No index is pre-populated in this instance's view of the damaged record: recovery is by digest.
            val restarted = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            val summary = restarted.quarantineStore().listQuarantined(10).single { it.locator == damagedDigest }

            assertThat(summary.locator).isEqualTo(damagedDigest)
            assertThat(summary.outboxId).isNull()
            assertThat(summary.eventKey).isNull()
            assertThat(summary.payloadDigest).startsWith("sha256:")
            assertThat(summary.quarantinedAt).isEqualTo(now)
            assertThat(summary.failureDetail).isNotNull()
            assertThat(summary.failureDetail!!.length)
                .isLessThanOrEqualTo(SovereignOpsAuditOutboxQuarantineStore.MAX_FAILURE_DETAIL)
            val storedBytes = restarted.quarantineStore().readQuarantinedCiphertext(damagedDigest)
            assertThat(storedBytes?.toList()).isEqualTo(originalBytes)
            // A locator that is not quarantined, or not a digest at all, resolves to nothing.
            assertThat(restarted.quarantineStore().readQuarantinedCiphertext("d".repeat(64))).isNull()
            assertThat(restarted.quarantineStore().readQuarantinedCiphertext("../../etc/passwd")).isNull()
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
                runBlocking { reading.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now) }
            }.hasMessage(SovereignOpsAuditOutboxQuarantineStore.ERROR_DECRYPTION_UNAVAILABLE)

            assertThat(reading.quarantineStore().listQuarantined(10)).isEmpty()
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

            val report = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            assertThat(report.scanned).isEqualTo(3)
            assertThat(report.quarantined.map { it.locator }).containsExactly(damagedDigest)
            assertThat(store.get("untouched-healthy")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            assertThat(store.get("untouched-prepared")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PREPARED)
            assertThat(store.quarantineStore().listQuarantined(10).map { it.locator }).containsExactly(damagedDigest)
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
            assertThat(store.quarantineStore().listQuarantined(10)).hasSize(1)
            assertThat(store.get("dispatch-healthy")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
        }

    // --- eligibility, scan progress and future schema versions ---------------------------------

    @Test
    fun `a damaged PREPARED record is marked rather than trusted, and its bytes stay retrievable`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.append(prepared("damaged-prepared"))
            store.appendDispatchable(refusal("prepared-control"))
            // This backend seals the status inside the payload, so a record that cannot be decrypted cannot
            // prove it is PENDING -- or PREPARED. The marker is the documented, reversible consequence.
            val digest = corruptSealedFile(FileStoreSha256.digest(RECORD_TYPE, "damaged-prepared"))
            val before = snapshot()

            val report = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            assertThat(report.quarantined.map { it.locator }).containsExactly(digest)
            assertThat(report.quarantined.single().label)
                .isEqualTo(SovereignOpsAuditOutboxQuarantineStore.LABEL_UNDECODABLE)
            // Nothing is destroyed: the damaged record keeps its bytes and stays readable through recovery.
            assertThat(snapshot()).isEqualTo(before)
            val stored = store.quarantineStore().readQuarantinedCiphertext(digest)
            assertThat(stored?.toList()).isEqualTo(before.getValue("$digest.tram.enc"))
            // And the marker is metadata-only: dropping it restores the file to ordinary handling.
            Files.delete(markerPath(digest))
            assertThat(store.quarantineStore().listQuarantined(10)).isEmpty()
            assertThat(store.get("prepared-control")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
        }

    @Test
    fun `recovery decrypts at most limit records per pass and still reaches damage behind a healthy prefix`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            repeat(30) { store.appendDispatchable(refusal("prefix-$it")) }
            // "f"*64 sorts after every other digest, so the damage sits strictly behind 30 healthy records.
            val damagedDigest = writeUndecodableFile("f".repeat(64))

            val scanned = mutableListOf<Int>()
            var found: String? = null
            repeat(10) {
                val report = store.quarantineStore().quarantineUndecodableEvidence(limit = 4, now = now)
                scanned.add(report.scanned)
                report.quarantined.singleOrNull()?.let { found = it.locator }
            }

            // Bounded work: the SPI's limit bounds decrypted records, never the whole archive.
            assertThat(scanned).allMatch { it <= 4 }
            // And progress: the durable cursor reaches the damage within ceil(31 / 4) passes.
            assertThat(found).isEqualTo(damagedDigest)
        }

    @Test
    fun `two concurrent recovery passes produce exactly one quarantine`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("concurrent-control"))
            val digest = writeUndecodableFile("e".repeat(64))
            val recovery = store.quarantineStore()

            val reports =
                coroutineScope {
                    (1..2)
                        .map { async(Dispatchers.IO) { recovery.quarantineUndecodableEvidence(limit = 10, now = now) } }
                        .awaitAll()
                }

            assertThat(reports.sumOf { it.quarantined.size }).isEqualTo(1)
            val markers = store.quarantineStore().listQuarantined(10)
            assertThat(markers.map { it.locator }).containsExactly(digest)
            assertThat(markers.single().quarantinedAt).isEqualTo(now)
        }

    @Test
    fun `an append fails closed after a restart while a damaged record is still unindexable`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("key-holder"))
            // A healthy control: recovery must be able to prove the key is usable before it quarantines.
            store.appendDispatchable(refusal("key-control"))
            corruptSealedFile(FileStoreSha256.digest(RECORD_TYPE, "key-holder"))

            // The event key lives inside the ciphertext, so a cold restart cannot index it. Rather than
            // silently freeing the key for a different outbox id, appends refuse until recovery resolves it.
            val restarted = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            assertThatThrownBy {
                runBlocking { restarted.appendDispatchable(refusal("key-thief").copy(eventKey = "event-key-holder")) }
            }.hasMessageContaining("tramai-sovereign-ops-outbox-event-key-uniqueness-unavailable")

            // Quarantine resolves it: a quarantined record is permanently unemittable, so its key can no
            // longer produce a second emission. Healthy evidence keeps dispatching and appends resume.
            restarted.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)
            restarted.appendDispatchable(refusal("key-fresh"))
            val claimed = restarted.claimPending("dispatcher", 10, now.plusSeconds(1)).map { it.outboxId }
            assertThat(claimed).contains("key-fresh", "key-control")
            assertThat(claimed).doesNotContain("key-holder")
        }

    @Test
    fun `a record from a newer schema version is preserved instead of quarantined`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("future-control"))
            val digest = writeUnsupportedVersionFile("d".repeat(64))
            val before = snapshot().getValue("$digest.tram.enc")

            val report = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            // It decrypts fine, so it is not damaged: a rollback must not cost the evidence permanently.
            assertThat(report.quarantined).isEmpty()
            assertThat(markerPath(digest)).doesNotExist()
            assertThat(snapshot().getValue("$digest.tram.enc")).isEqualTo(before)
            assertThat(store.claimPending("dispatcher", 10, now.plusSeconds(1)).map { it.outboxId })
                .containsExactly("future-control")
        }

    @Test
    fun `scan progress stages its temporary file inside the store root`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("cursor-control"))
            writeUndecodableFile("f".repeat(64))
            val above = namesIn(root.parent)

            store.quarantineStore().quarantineUndecodableEvidence(limit = 1, now = now)

            // The cursor is the store's own state: staging it above the root would need write access the
            // deployment does not grant, and would leak a temp file outside the configured directory.
            assertThat(namesIn(root.parent)).isEqualTo(above)
            assertThat(root.resolve("quarantine-scan.cursor")).exists()
        }

    @Test
    fun `recovery works when the directory above the store root is read only`() =
        runBlocking<Unit> {
            assumeTrue("root" != System.getProperty("user.name"))
            val storeRoot = Files.createDirectory(root.resolve("hardened"))
            val store = FileSovereignOpsAuditOutboxStore(root = storeRoot, key = key)
            store.appendDispatchable(refusal("hardened-control"))
            val digest = "9".repeat(64)
            Files.writeString(
                storeRoot.resolve(RECORD_TYPE).resolve("$digest.tram.enc"),
                """{"envelopeVersion":1,"recordType":"$RECORD_TYPE","recordKeyDigest":"$digest",""" +
                    """"keyId":"default","nonceBase64":"AAAAAAAAAAAAAAAA","ciphertextBase64":"YmFk"}""",
                StandardOpenOption.CREATE_NEW,
            )
            Files.setPosixFilePermissions(
                storeRoot.resolve(RECORD_TYPE).resolve("$digest.tram.enc"),
                PosixFilePermissions.fromString("rw-------"),
            )
            val above = storeRoot.parent
            Files.setPosixFilePermissions(above, PosixFilePermissions.fromString("r-x------"))
            try {
                val report = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

                assertThat(report.quarantined.map { it.locator }).containsExactly(digest)
            } finally {
                Files.setPosixFilePermissions(above, PosixFilePermissions.fromString("rwx------"))
            }
        }

    @Test
    fun `an existing marker is never rewritten by a later pass`() =
        runBlocking<Unit> {
            val store = FileSovereignOpsAuditOutboxStore(root = root, key = key)
            store.appendDispatchable(refusal("marker-control"))
            val first = writeUndecodableFile("b".repeat(64))
            val second = writeUndecodableFile("c".repeat(64))

            store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)
            // A marker on disk is another pass's publication: later passes leave those bytes alone instead
            // of replacing them with their own metadata. Publication atomicity itself comes from link();
            // the scan-level filter and per-record lock make it unobservable from a single process.
            val published = Files.readAllBytes(markerPath(first))
            Files.write(markerPath(second), published)

            val report = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            assertThat(report.quarantined).isEmpty()
            assertThat(Files.readAllBytes(markerPath(second)).toList()).isEqualTo(published.toList())
        }

    // --- helpers --------------------------------------------------------------------------------

    /** Re-seals a committed record with an unreadable payload, keeping a structurally valid envelope. */
    private fun corruptSealedFile(digest: String): String {
        val target = outboxDir().resolve("$digest.tram.enc")
        val envelope = EncryptedFileEnvelopeV1.fromJson(Files.readString(target))
        val corrupted =
            EncryptedFileEnvelopeV1(
                envelopeVersion = envelope.envelopeVersion,
                recordType = envelope.recordType,
                recordKeyDigest = envelope.recordKeyDigest,
                keyId = envelope.keyId,
                nonceBase64 = envelope.nonceBase64,
                ciphertextBase64 = "bm90LWEtY2lwaGVydGV4dA==",
            )
        Files.writeString(target, corrupted.toJson(), StandardOpenOption.TRUNCATE_EXISTING)
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
        return digest
    }

    /** Encrypts a payload declaring a schema version this build does not know. */
    private fun writeUnsupportedVersionFile(digest: String): String {
        val plaintext = """{"schemaVersion":99,"record":{},"governedRunIdentity":{}}"""
        val (nonce, ciphertext) =
            AesGcmFileEncryption.encrypt(
                key = key,
                recordType = RECORD_TYPE,
                recordKeyDigest = digest,
                keyId = KEY_ID,
                plaintextBytes = plaintext.toByteArray(),
            )
        val target = outboxDir().resolve("$digest.tram.enc")
        Files.writeString(
            target,
            EncryptedFileEnvelopeV1(
                envelopeVersion = 1,
                recordType = RECORD_TYPE,
                recordKeyDigest = digest,
                keyId = KEY_ID,
                nonceBase64 = nonce,
                ciphertextBase64 = ciphertext,
            ).toJson(),
            StandardOpenOption.CREATE_NEW,
        )
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
        return digest
    }

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

    private fun outboxDir(): Path = root.resolve(RECORD_TYPE)

    private fun namesIn(dir: Path): List<String> {
        val names = Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
        return names
    }

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

    private companion object {
        private const val RECORD_TYPE = "ops-audit-outbox"
        private const val KEY_ID = "default"
    }
}
