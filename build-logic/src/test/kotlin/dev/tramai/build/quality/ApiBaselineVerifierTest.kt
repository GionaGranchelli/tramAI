package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Focused production-path tests for ApiBaselineVerifier.
 * No Gradle project, no file I/O — pure logic on data classes.
 */
class ApiBaselineVerifierTest {

    private val verifier = ApiBaselineVerifier(
        repositoryRoot = File("/nonexistent"),
        catalogModules = mapOf(
            ":tramai-core" to testEntry(":tramai-core", "core-contracts", "published", "stable"),
            ":tramai-engine" to testEntry(":tramai-engine", "runtime-execution", "published", "preview"),
            ":tramai-bom" to testEntry(":tramai-bom", "core-contracts", "published", "stable"),
        )
    )

    private fun testEntry(
        path: String,
        layer: String,
        publishability: String,
        apiStability: String
    ): ModuleCatalog.ModuleEntry = ModuleCatalog.ModuleEntry(
        path = path,
        layer = ModuleLayer.fromYaml(layer) ?: error("bad layer $layer"),
        maturity = ModuleMaturity.fromYaml(if (apiStability == "stable") "stable" else "preview") ?: error("bad maturity"),
        publishability = ModulePublishability.fromYaml(publishability) ?: error("bad pub $publishability"),
        apiStability = ModuleApiStability.fromYaml(apiStability) ?: error("bad api $apiStability"),
        visibility = ModuleVisibility.fromYaml(if (publishability == "published") "public" else "internal") ?: error("bad vis"),
        owner = "test",
        dependencyPolicy = "core",
        releaseInclusion = ReleaseInclusion.fromYaml(if (publishability == "published") "included" else "internal_only") ?: error("bad rel"),
        rationale = "Test fixture entry."
    )

    private fun record(
        module: String = ":tramai-core",
        stability: String = "stable",
        applicable: Boolean = true,
        dumpPath: String = "tramai-core/api/tramai-core.api",
        sha256: String = "abc123",
        exclusionReason: String? = null
    ) = ApiDumpRecord(module, stability, applicable, dumpPath, sha256, exclusionReason)

    private fun baseline(records: List<ApiDumpRecord> = emptyList()): ApiBaseline {
        val json = ApiBaselineVerifier.deterministicJson(records)
        val hash = sha256Simple(json.toByteArray())
        return ApiBaseline(modules = records, aggregateHash = hash)
    }

    private fun sha256Simple(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `API records sorted deterministically`() {
        val unsorted = listOf(
            record(module = ":tramai-engine", stability = "preview"),
            record(module = ":tramai-core"),
        )
        val sorted = ApiBaselineVerifier.sortRecords(unsorted)
        assertEquals(":tramai-core", sorted[0].module)
        assertEquals(":tramai-engine", sorted[1].module)
    }

    @Test
    fun `Applicable module without dump fails on both committed and current`() {
        val rec = record(dumpPath = "", sha256 = "")
        val diags = verifier.verify(baseline(listOf(rec)), baseline(listOf(rec)))
        // Both committed and current missing dump → FAILURE
        val committedFailures = diags.filter { it.code == DiagnosticCode.API_DUMP_MISSING && it.severity == DiagnosticSeverity.FAILURE }
        assertTrue(committedFailures.any { it.message.startsWith("Committed") },
            "Applicable module without dump in committed should be FAILURE")
        assertTrue(committedFailures.any { it.message.startsWith("Current") },
            "Applicable module without dump in current should be FAILURE")
    }

    @Test
    fun `Non-applicable BOM module is explicitly excluded`() {
        val rec = record(
            module = ":tramai-bom",
            stability = "stable",
            applicable = false,
            dumpPath = "tramai-bom/api/tramai-bom.api",
            sha256 = "",
            exclusionReason = "module has no Kotlin or Java production sources"
        )
        val diags = verifier.verify(baseline(listOf(rec)), baseline(listOf(rec)))
        assertFalse(diags.any { it.code == DiagnosticCode.API_DUMP_MISSING },
            "Non-applicable module without dump should not fail")
    }

    @Test
    fun `Duplicate API dump ownership fails`() {
        val rec1 = record(module = ":tramai-core", dumpPath = "shared/api/shared.api")
        val rec2 = record(module = ":tramai-engine", dumpPath = "shared/api/shared.api", stability = "preview")
        val diags = verifier.verify(baseline(listOf(rec1, rec2)), baseline(listOf(rec1, rec2)))
        assertTrue(diags.any { it.code == DiagnosticCode.API_DUMP_DUPLICATE && it.severity == DiagnosticSeverity.FAILURE },
            "Expected API_DUMP_DUPLICATE failure when two modules claim same dump")
    }

    @Test
    fun `Dump path escaping repository fails`() {
        val rec = record(dumpPath = "../../../etc/passwd")
        val diags = verifier.verify(baseline(listOf(rec)), baseline(listOf(rec)))
        assertTrue(diags.any { it.code == DiagnosticCode.API_DUMP_MISSING },
            "Escaping dump path should fail")
    }

    @Test
    fun `Dump path with absolute path fails`() {
        val rec = record(dumpPath = "/home/user/tramai-core/api/tramai-core.api")
        val diags = verifier.verify(baseline(listOf(rec)), baseline(listOf(rec)))
        assertTrue(diags.any { it.code == DiagnosticCode.API_DUMP_MISSING },
            "Absolute dump path should fail")
    }

    @Test
    fun `Two equivalent inputs produce byte-identical JSON`() {
        val records = listOf(
            record(module = ":tramai-core"),
            record(module = ":tramai-engine", stability = "preview"),
        )
        val json1 = ApiBaselineVerifier.deterministicJson(records)
        val json2 = ApiBaselineVerifier.deterministicJson(records.shuffled())
        assertEquals(json1, json2, "Deterministic JSON should be byte-identical regardless of input order")
    }

    @Test
    fun `Empty baseline fails`() {
        val diags = verifier.verify(baseline(), baseline())
        assertTrue(diags.any { it.code == DiagnosticCode.API_BASELINE_EMPTY },
            "Empty baseline should produce API_BASELINE_EMPTY")
    }

    @Test
    fun `Module with unrecognized apiStability produces warning`() {
        val rec = record(module = ":tramai-unknown", stability = "unknown")
        val diags = verifier.verify(baseline(listOf(rec)), baseline(listOf(rec)))
        assertTrue(diags.any { it.code == DiagnosticCode.API_MODULE_UNCLASSIFIED },
            "Unrecognized stability should produce warning")
    }

    @Test
    fun `Non-empty baseline with valid records passes`() {
        val localVerifier = ApiBaselineVerifier(
            repositoryRoot = File("/nonexistent"),
            catalogModules = mapOf(
                ":tramai-core" to testEntry(":tramai-core", "core-contracts", "published", "stable"),
                ":tramai-engine" to testEntry(":tramai-engine", "runtime-execution", "published", "preview"),
            ),
            apiValidationModules = setOf(":tramai-core")
        )
        val records = listOf(
            record(module = ":tramai-core", dumpPath = "tramai-core/api/tramai-core.api"),
            record(module = ":tramai-engine", stability = "preview", dumpPath = "tramai-engine/api/tramai-engine.api"),
        )
        val diags = localVerifier.verify(baseline(records), baseline(records))
        val failures = diags.filter { it.severity == DiagnosticSeverity.FAILURE }
        assertTrue(failures.isEmpty(), "Valid baseline should have no failures: $failures")
    }

    @Test
    fun `Aggregate hash change produces warning not failure`() {
        val localVerifier = ApiBaselineVerifier(
            repositoryRoot = File("/nonexistent"),
            catalogModules = mapOf(
                ":tramai-core" to testEntry(":tramai-core", "core-contracts", "published", "stable"),
            ),
            apiValidationModules = setOf(":tramai-core")
        )
        val records = listOf(record(module = ":tramai-core"))
        val committed = baseline(records)
        val current = ApiBaseline(modules = records, aggregateHash = "different-hash")
        val diags = localVerifier.verify(committed, current)
        assertTrue(diags.any { it.code == DiagnosticCode.API_HASH_CHANGED && it.severity == DiagnosticSeverity.WARNING },
            "Hash change should be a WARNING, not FAILURE")
        assertFalse(diags.any { it.severity == DiagnosticSeverity.FAILURE },
            "Hash change alone should not cause FAILURE")
    }

    @Test
    fun `Excluded module with code source is correctly excluded`() {
        val rec = record(
            module = ":tramai-testing",
            stability = "internal",
            applicable = false,
            dumpPath = "tramai-testing/api/tramai-testing.api",
            sha256 = "",
            exclusionReason = "module has apiStability 'excluded'"
        )
        val diags = verifier.verify(baseline(listOf(rec)), baseline(listOf(rec)))
        assertFalse(diags.any { it.code == DiagnosticCode.API_DUMP_MISSING },
            "Excluded module should not produce API_DUMP_MISSING")
    }

    @Test
    fun `Stable module has correct stability field`() {
        val rec = record(module = ":tramai-core", stability = "stable")
        assertEquals("stable", rec.stability)
    }
}
